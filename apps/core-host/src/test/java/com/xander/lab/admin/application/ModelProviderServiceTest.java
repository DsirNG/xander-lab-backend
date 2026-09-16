package com.xander.lab.admin.application;

import com.xander.lab.admin.api.CreateProviderRequest;
import com.xander.lab.admin.api.ModelProviderView;
import com.xander.lab.admin.api.UpdateProviderRequest;
import com.xander.lab.admin.domain.ModelProvider;
import com.xander.lab.admin.infrastructure.FeatureModelConfigMapper;
import com.xander.lab.admin.infrastructure.ModelGatewayProperties;
import com.xander.lab.admin.infrastructure.ModelProviderMapper;
import com.xander.lab.admin.infrastructure.ProviderKeyCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModelProviderServiceTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private ModelGatewayProperties properties;
    private ProviderKeyCipher cipher;
    private ModelProviderService service;

    @Mock
    private ModelProviderMapper mapper;
    @Mock
    private FeatureModelConfigMapper configMapper;

    @BeforeEach
    void setUp() {
        properties = new ModelGatewayProperties();
        properties.setEnabled(true);
        properties.setEncryptionKey(KEY);
        cipher = new ProviderKeyCipher(properties);
        cipher.initialize();
        service = new ModelProviderService(properties, mapper, configMapper, cipher);
    }

    @Test
    void createFailsFastWhenGatewayIsDisabled() {
        properties.setEnabled(false);

        assertThatThrownBy(() -> service.create(request("openai", "https://api.openai.com/v1", "sk-x")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(503);
    }

    @Test
    void createEncryptsKeyAndViewNeverLeaksIt() {
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.selectById(any())).thenAnswer(invocation -> {
            ModelProvider stored = new ModelProvider();
            stored.setId(invocation.getArgument(0));
            stored.setName("openai");
            stored.setVendor("OPENAI_COMPATIBLE");
            stored.setBaseUrl("https://api.openai.com/v1");
            stored.setEncryptedApiKey(cipher.encrypt("sk-secret"));
            stored.setEnabled(true);
            stored.setPriority(0);
            return stored;
        });

        ModelProviderView view = service.create(request("openai", "https://api.openai.com/v1", "sk-secret"));

        ArgumentCaptor<ModelProvider> captured = ArgumentCaptor.forClass(ModelProvider.class);
        verify(mapper).insert(captured.capture());
        assertThat(captured.getValue().getEncryptedApiKey()).isNotEqualTo("sk-secret");
        assertThat(captured.getValue().getEncryptedApiKey()).contains(".");
        assertThat(view.apiKeyConfigured()).isTrue();
        assertThat(view.baseUrl()).isEqualTo("https://api.openai.com/v1");
    }

    @Test
    void createRejectsDuplicateNames() {
        when(mapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.create(request("openai", "https://api.openai.com/v1", "sk-x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已存在");
    }

    @Test
    void createRejectsNonHttpBaseUrl() {
        assertThatThrownBy(() -> service.create(request("openai", "not-a-url", "sk-x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http(s)");
        verify(mapper, never()).insert(any());
    }

    @Test
    void updateWithoutApiKeyKeepsTheEncryptedKeyUntouched() {
        ModelProvider existing = new ModelProvider();
        existing.setId(1L);
        existing.setName("openai");
        existing.setBaseUrl("https://api.openai.com/v1");
        existing.setEncryptedApiKey("existing-ciphertext");
        existing.setEnabled(true);
        existing.setPriority(0);
        when(mapper.selectById(1L)).thenReturn(existing);
        when(mapper.selectList(any())).thenReturn(List.of(existing));

        service.update(1L, new UpdateProviderRequest(
                "openai", "OPENAI_COMPATIBLE", "https://api.openai.com/v1", null, null, true, 0));

        ArgumentCaptor<ModelProvider> captured = ArgumentCaptor.forClass(ModelProvider.class);
        verify(mapper).updateById(captured.capture());
        assertThat(captured.getValue().getEncryptedApiKey()).isEqualTo("existing-ciphertext");

        when(mapper.selectById(1L)).thenReturn(captured.getValue());
        service.update(1L, new UpdateProviderRequest(
                "openai", "OPENAI_COMPATIBLE", "https://api.openai.com/v1", "sk-new", null, true, 0));
        verify(mapper, org.mockito.Mockito.times(2)).updateById(captured.capture());
        assertThat(captured.getValue().getEncryptedApiKey()).isNotEqualTo("existing-ciphertext");
        assertThat(cipher.decrypt(captured.getValue().getEncryptedApiKey())).isEqualTo("sk-new");
    }

    @Test
    void deleteIsRejectedWhileReferencedByFeatureConfigs() {
        ModelProvider existing = new ModelProvider();
        existing.setId(1L);
        when(mapper.selectById(1L)).thenReturn(existing);
        when(configMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(409);
        verify(mapper, never()).deleteById(any(java.io.Serializable.class));
    }

    @Test
    void deleteSucceedsWhenUnreferenced() {
        ModelProvider existing = new ModelProvider();
        existing.setId(1L);
        when(mapper.selectById(1L)).thenReturn(existing);
        when(configMapper.selectCount(any())).thenReturn(0L);

        service.delete(1L);

        verify(mapper).deleteById((java.io.Serializable) 1L);
    }

    private CreateProviderRequest request(String name, String baseUrl, String apiKey) {
        return new CreateProviderRequest(name, "OPENAI_COMPATIBLE", baseUrl, apiKey, null, true, 0);
    }
}