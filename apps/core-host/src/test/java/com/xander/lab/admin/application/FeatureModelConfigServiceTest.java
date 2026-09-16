package com.xander.lab.admin.application;

import com.xander.lab.admin.api.FeatureConfigView;
import com.xander.lab.admin.api.UpdateFeatureConfigRequest;
import com.xander.lab.admin.domain.FeatureModelConfig;
import com.xander.lab.admin.domain.ModelProvider;
import com.xander.lab.admin.infrastructure.FeatureModelConfigMapper;
import com.xander.lab.admin.infrastructure.ModelGatewayProperties;
import com.xander.lab.admin.infrastructure.ModelProviderMapper;
import com.xander.lab.core.api.ModelGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeatureModelConfigServiceTest {

    private ModelGatewayProperties properties;
    private FeatureModelConfigService service;

    @Mock
    private FeatureModelConfigMapper configMapper;
    @Mock
    private ModelProviderMapper providerMapper;

    @BeforeEach
    void setUp() {
        properties = new ModelGatewayProperties();
        properties.setEnabled(true);
        service = new FeatureModelConfigService(properties, configMapper, providerMapper);
    }

    @Test
    void listReturnsAllFiveFeaturesWithDefaultEmptyViews() {
        when(configMapper.selectList(any())).thenReturn(List.of());

        List<FeatureConfigView> views = service.listConfigs();

        assertThat(views).hasSize(5);
        assertThat(views).extracting(FeatureConfigView::featureKey)
                .containsExactly("agent", "blog_agent", "blog_agent_image", "img2three_vision", "recitation_asr");
        assertThat(views).allMatch(view -> !view.enabled());
    }

    @Test
    void updateInsertsAConfigWhenNoneExists() {
        when(configMapper.selectOne(any())).thenReturn(null);
        when(providerMapper.selectById(1L)).thenReturn(enabledProvider(1L));
        when(providerMapper.selectById(2L)).thenReturn(enabledProvider(2L));
        when(configMapper.selectList(any())).thenReturn(List.of());

        service.update("agent", new UpdateFeatureConfigRequest(
                true, 1L, "gpt-4o", ModelGateway.API_STYLE_RESPONSES,
                2L, "gpt-4o-mini", ModelGateway.API_STYLE_RESPONSES));

        ArgumentCaptor<FeatureModelConfig> captured = ArgumentCaptor.forClass(FeatureModelConfig.class);
        verify(configMapper).insert(captured.capture());
        assertThat(captured.getValue().getFeatureKey()).isEqualTo("agent");
        assertThat(captured.getValue().getPrimaryModel()).isEqualTo("gpt-4o");
        assertThat(captured.getValue().getPrimaryApiStyle()).isEqualTo(ModelGateway.API_STYLE_RESPONSES);
        assertThat(captured.getValue().getFallbackProviderId()).isEqualTo(2L);
        assertThat(captured.getValue().getFallbackApiStyle()).isEqualTo(ModelGateway.API_STYLE_RESPONSES);
    }

    @Test
    void updateNormalizesBlankModelsAndStylesToNull() {
        when(configMapper.selectOne(any())).thenReturn(null);
        when(providerMapper.selectById(1L)).thenReturn(enabledProvider(1L));
        when(configMapper.selectList(any())).thenReturn(List.of());

        service.update("blog_agent", new UpdateFeatureConfigRequest(
                true, 1L, "  gpt-4o  ", "  RESPONSES  ", null, "   ", " "));

        ArgumentCaptor<FeatureModelConfig> captured = ArgumentCaptor.forClass(FeatureModelConfig.class);
        verify(configMapper).insert(captured.capture());
        assertThat(captured.getValue().getPrimaryModel()).isEqualTo("gpt-4o");
        assertThat(captured.getValue().getPrimaryApiStyle()).isEqualTo(ModelGateway.API_STYLE_RESPONSES);
        assertThat(captured.getValue().getFallbackModel()).isNull();
        assertThat(captured.getValue().getFallbackProviderId()).isNull();
        assertThat(captured.getValue().getFallbackApiStyle()).isNull();
    }

    @Test
    void updateRejectsUnknownFeatureKey() {
        assertThatThrownBy(() -> service.update("unknown", new UpdateFeatureConfigRequest(
                true, 1L, "m", null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知的功能标识");
    }

    @Test
    void updateRejectsSlotWithProviderButNoModel() {
        assertThatThrownBy(() -> service.update("agent", new UpdateFeatureConfigRequest(
                true, 1L, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("同时填写");
        verify(configMapper, never()).insert(any());
    }

    @Test
    void updateRejectsSlotWithProviderButNoApiStyle() {
        assertThatThrownBy(() -> service.update("agent", new UpdateFeatureConfigRequest(
                true, 1L, "gpt-4o", null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("同时填写");
        verify(configMapper, never()).insert(any());
    }

    @Test
    void updateRejectsUnknownApiStyle() {
        assertThatThrownBy(() -> service.update("agent", new UpdateFeatureConfigRequest(
                true, 1L, "gpt-4o", "WEIRD", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("接口风格必须是");
        verify(configMapper, never()).insert(any());
    }

    @Test
    void updateRejectsResponsesStyleForImageFeature() {
        assertThatThrownBy(() -> service.update("blog_agent_image", new UpdateFeatureConfigRequest(
                true, 1L, "gpt-image-2", ModelGateway.API_STYLE_RESPONSES, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持 RESPONSES");
        verify(configMapper, never()).insert(any());
    }

    @Test
    void updateRejectsImagesStyleForTextFeature() {
        assertThatThrownBy(() -> service.update("agent", new UpdateFeatureConfigRequest(
                true, 1L, "gpt-4o", ModelGateway.API_STYLE_IMAGES_GENERATIONS, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("图片专用接口风格");
        verify(configMapper, never()).insert(any());
    }

    @Test
    void updateRejectsChatPromptStyleForTextFeature() {
        assertThatThrownBy(() -> service.update("blog_agent", new UpdateFeatureConfigRequest(
                true, 1L, "gpt-4o", ModelGateway.API_STYLE_CHAT_PROMPT, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("图片专用接口风格");
        verify(configMapper, never()).insert(any());
    }

    @Test
    void updateAcceptsChatPromptStyleForImageFeature() {
        when(configMapper.selectOne(any())).thenReturn(null);
        when(providerMapper.selectById(1L)).thenReturn(enabledProvider(1L));
        when(configMapper.selectList(any())).thenReturn(List.of());

        service.update("blog_agent_image", new UpdateFeatureConfigRequest(
                true, 1L, "gpt-image-2", ModelGateway.API_STYLE_CHAT_PROMPT, null, null, null));

        ArgumentCaptor<FeatureModelConfig> captured = ArgumentCaptor.forClass(FeatureModelConfig.class);
        verify(configMapper).insert(captured.capture());
        assertThat(captured.getValue().getPrimaryApiStyle()).isEqualTo(ModelGateway.API_STYLE_CHAT_PROMPT);
    }

    @Test
    void updateAcceptsDashScopeStyleForRecitationFeature() {
        when(configMapper.selectOne(any())).thenReturn(null);
        when(providerMapper.selectById(1L)).thenReturn(enabledProvider(1L));
        when(configMapper.selectList(any())).thenReturn(List.of());

        service.update(ModelGateway.FEATURE_RECITATION_ASR, new UpdateFeatureConfigRequest(
                true, 1L, "paraformer-v2", ModelGateway.API_STYLE_DASHSCOPE_ASR, null, null, null));

        ArgumentCaptor<FeatureModelConfig> captured = ArgumentCaptor.forClass(FeatureModelConfig.class);
        verify(configMapper).insert(captured.capture());
        assertThat(captured.getValue().getPrimaryApiStyle()).isEqualTo(ModelGateway.API_STYLE_DASHSCOPE_ASR);
    }

    @Test
    void updateRejectsChatStyleForRecitationFeature() {
        assertThatThrownBy(() -> service.update(ModelGateway.FEATURE_RECITATION_ASR,
                new UpdateFeatureConfigRequest(true, 1L, "paraformer-v2",
                        ModelGateway.API_STYLE_CHAT_COMPLETIONS, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DASHSCOPE_ASR");
        verify(configMapper, never()).insert(any());
    }

    @Test
    void updateRejectsDisabledOrMissingProvider() {
        when(providerMapper.selectById(9L)).thenReturn(null);

        assertThatThrownBy(() -> service.update("agent", new UpdateFeatureConfigRequest(
                true, 9L, "gpt-4o", ModelGateway.API_STYLE_RESPONSES, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在或已停用");
    }

    @Test
    void updateRejectsDisabledConfigThatStillCarriesSlots() {
        when(providerMapper.selectById(1L)).thenReturn(enabledProvider(1L));

        assertThatThrownBy(() -> service.update("agent", new UpdateFeatureConfigRequest(
                false, 1L, "gpt-4o", ModelGateway.API_STYLE_RESPONSES, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未启用");
        verify(configMapper, never()).insert(any());
        verify(configMapper, never()).updateById(any());
    }

    @Test
    void updateWithoutProvidersClearsTheConfig() {
        FeatureModelConfig existing = new FeatureModelConfig();
        existing.setId(5L);
        existing.setFeatureKey("agent");
        when(configMapper.selectOne(any())).thenReturn(existing);
        when(configMapper.selectList(any())).thenReturn(List.of());

        service.update("agent", new UpdateFeatureConfigRequest(false, null, null, null, null, null, null));

        ArgumentCaptor<FeatureModelConfig> captured = ArgumentCaptor.forClass(FeatureModelConfig.class);
        verify(configMapper).updateById(captured.capture());
        assertThat(captured.getValue().getEnabled()).isFalse();
        assertThat(captured.getValue().getPrimaryProviderId()).isNull();
        assertThat(captured.getValue().getPrimaryModel()).isNull();
        assertThat(captured.getValue().getPrimaryApiStyle()).isNull();
    }

    @Test
    void updateRejectsDuplicatePrimaryAndFallbackSlot() {
        when(providerMapper.selectById(1L)).thenReturn(enabledProvider(1L));

        assertThatThrownBy(() -> service.update("blog_agent_image", new UpdateFeatureConfigRequest(
                true, 1L, "gpt-image-2", ModelGateway.API_STYLE_CHAT_PROMPT,
                1L, "gpt-image-2", ModelGateway.API_STYLE_CHAT_COMPLETIONS)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("兜底无意义");
        verify(configMapper, never()).insert(any());
        verify(configMapper, never()).updateById(any());
    }

    @Test
    void updateAllowsSameProviderWithDifferentFallbackModel() {
        when(configMapper.selectOne(any())).thenReturn(null);
        when(providerMapper.selectById(1L)).thenReturn(enabledProvider(1L));
        when(configMapper.selectList(any())).thenReturn(List.of());

        service.update("blog_agent_image", new UpdateFeatureConfigRequest(
                true, 1L, "gpt-image-2", ModelGateway.API_STYLE_CHAT_PROMPT,
                1L, "gpt-image-1", ModelGateway.API_STYLE_CHAT_PROMPT));

        ArgumentCaptor<FeatureModelConfig> captured = ArgumentCaptor.forClass(FeatureModelConfig.class);
        verify(configMapper).insert(captured.capture());
        assertThat(captured.getValue().getFallbackModel()).isEqualTo("gpt-image-1");
    }

    private ModelProvider enabledProvider(Long id) {
        ModelProvider provider = new ModelProvider();
        provider.setId(id);
        provider.setEnabled(true);
        return provider;
    }
}
