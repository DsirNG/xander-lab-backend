package com.xander.lab.admin.application;

import com.xander.lab.admin.application.ModelGateway.ModelEndpoint;
import com.xander.lab.admin.domain.FeatureModelConfig;
import com.xander.lab.admin.domain.ModelProvider;
import com.xander.lab.admin.infrastructure.FeatureModelConfigMapper;
import com.xander.lab.admin.infrastructure.ModelGatewayProperties;
import com.xander.lab.admin.infrastructure.ModelProviderMapper;
import com.xander.lab.admin.infrastructure.ProviderKeyCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModelGatewayServiceTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private ModelGatewayProperties properties;
    private ProviderKeyCipher cipher;
    private ModelGatewayService service;

    @Mock
    private FeatureModelConfigMapper configMapper;
    @Mock
    private ModelProviderMapper providerMapper;

    @BeforeEach
    void setUp() {
        properties = new ModelGatewayProperties();
        properties.setEnabled(true);
        properties.setEncryptionKey(KEY);
        cipher = new ProviderKeyCipher(properties);
        cipher.initialize();
        service = new ModelGatewayService(properties, configMapper, providerMapper, cipher);
    }

    private ModelProvider enabledProvider(Long id) {
        ModelProvider provider = new ModelProvider();
        provider.setId(id);
        provider.setEnabled(true);
        provider.setBaseUrl("https://api.example.com/v1/");
        provider.setEncryptedApiKey(cipher.encrypt("sk-secret"));
        return provider;
    }

    @Test
    void returnsEmptyWhenGatewayIsDisabled() {
        properties.setEnabled(false);

        assertThat(service.candidates(ModelGateway.FEATURE_AGENT)).isEmpty();
        verify(configMapper, never()).selectOne(any());
    }

    @Test
    void throwsWhenFeatureHasNoConfigRow() {
        when(configMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.candidates(ModelGateway.FEATURE_BLOG_AGENT))
                .isInstanceOf(FeatureModelNotConfiguredException.class)
                .hasMessageContaining("blog_agent");
    }

    @Test
    void throwsWhenFeatureConfigIsDisabled() {
        when(configMapper.selectOne(any())).thenReturn(config(false, 1L, "gpt-a", 2L, "gpt-b"));

        assertThatThrownBy(() -> service.candidates(ModelGateway.FEATURE_BLOG_AGENT))
                .isInstanceOf(FeatureModelNotConfiguredException.class);
        verify(providerMapper, never()).selectById(any());
    }

    @Test
    void returnsPrimaryAndFallbackInOrderWithResolvedCredentials() {
        when(configMapper.selectOne(any())).thenReturn(config(true, 1L, "gpt-a", 2L, "gpt-b"));
        when(providerMapper.selectById(1L)).thenReturn(enabledProvider(1L));
        when(providerMapper.selectById(2L)).thenReturn(enabledProvider(2L));

        List<ModelEndpoint> endpoints = service.candidates(ModelGateway.FEATURE_AGENT);

        assertThat(endpoints).hasSize(2);
        assertThat(endpoints.get(0).providerId()).isEqualTo(1L);
        assertThat(endpoints.get(0).baseUrl()).isEqualTo("https://api.example.com/v1");
        assertThat(endpoints.get(0).apiKey()).isEqualTo("sk-secret");
        assertThat(endpoints.get(0).model()).isEqualTo("gpt-a");
        assertThat(endpoints.get(0).apiStyle()).isEqualTo(ModelGateway.API_STYLE_RESPONSES);
        assertThat(endpoints.get(1).providerId()).isEqualTo(2L);
        assertThat(endpoints.get(1).model()).isEqualTo("gpt-b");
        assertThat(endpoints.get(1).apiStyle()).isEqualTo(ModelGateway.API_STYLE_RESPONSES);
    }

    @Test
    void appliesPerFeatureApiStyleDefaultsWhenConfigLeavesStyleBlank() {
        when(configMapper.selectOne(any())).thenReturn(config(true, 1L, "gpt-image-2", null, null));
        when(providerMapper.selectById(1L)).thenReturn(enabledProvider(1L));

        List<ModelEndpoint> endpoints = service.candidates(ModelGateway.FEATURE_BLOG_AGENT_IMAGE);

        assertThat(endpoints).hasSize(1);
        assertThat(endpoints.get(0).apiStyle()).isEqualTo(ModelGateway.API_STYLE_CHAT_COMPLETIONS);
    }

    @Test
    void passesThroughConfiguredApiStyles() {
        FeatureModelConfig configured = config(true, 1L, "gpt-image-2", null, null);
        configured.setPrimaryApiStyle(ModelGateway.API_STYLE_CHAT_COMPLETIONS);
        when(configMapper.selectOne(any())).thenReturn(configured);
        when(providerMapper.selectById(1L)).thenReturn(enabledProvider(1L));

        List<ModelEndpoint> endpoints = service.candidates(ModelGateway.FEATURE_BLOG_AGENT_IMAGE);

        assertThat(endpoints).hasSize(1);
        assertThat(endpoints.get(0).apiStyle()).isEqualTo(ModelGateway.API_STYLE_CHAT_COMPLETIONS);
    }

    @Test
    void throwsWhenAllProvidersAreUnusable() {
        when(configMapper.selectOne(any())).thenReturn(config(true, 1L, "gpt-a", 2L, "gpt-b"));
        ModelProvider disabled = enabledProvider(1L);
        disabled.setEnabled(false);
        when(providerMapper.selectById(1L)).thenReturn(disabled);
        when(providerMapper.selectById(2L)).thenReturn(null);

        assertThatThrownBy(() -> service.candidates(ModelGateway.FEATURE_AGENT))
                .isInstanceOf(FeatureModelNotConfiguredException.class);
    }

    @Test
    void throwsWhenAllKeysCannotBeDecrypted() {
        when(configMapper.selectOne(any())).thenReturn(config(true, 1L, "gpt-a", null, null));
        ModelProvider broken = enabledProvider(1L);
        broken.setEncryptedApiKey("not-a-ciphertext");
        when(providerMapper.selectById(1L)).thenReturn(broken);

        assertThatThrownBy(() -> service.candidates(ModelGateway.FEATURE_AGENT))
                .isInstanceOf(FeatureModelNotConfiguredException.class);
    }

    @Test
    void throwsWhenAllSlotsAreBlank() {
        when(configMapper.selectOne(any())).thenReturn(config(true, null, null, null, null));

        assertThatThrownBy(() -> service.candidates(ModelGateway.FEATURE_BLOG_AGENT_IMAGE))
                .isInstanceOf(FeatureModelNotConfiguredException.class);
    }

    private FeatureModelConfig config(boolean enabled, Long primaryId, String primaryModel,
                                      Long fallbackId, String fallbackModel) {
        FeatureModelConfig config = new FeatureModelConfig();
        config.setFeatureKey("agent");
        config.setEnabled(enabled);
        config.setPrimaryProviderId(primaryId);
        config.setPrimaryModel(primaryModel);
        config.setFallbackProviderId(fallbackId);
        config.setFallbackModel(fallbackModel);
        return config;
    }
}