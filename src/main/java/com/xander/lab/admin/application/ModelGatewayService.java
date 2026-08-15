package com.xander.lab.admin.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xander.lab.admin.domain.FeatureModelConfig;
import com.xander.lab.admin.domain.ModelProvider;
import com.xander.lab.admin.infrastructure.FeatureModelConfigMapper;
import com.xander.lab.admin.infrastructure.ModelGatewayProperties;
import com.xander.lab.admin.infrastructure.ModelProviderMapper;
import com.xander.lab.admin.infrastructure.ProviderKeyCipher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 模型网关实现：从 model_provider / feature_model_config 读取并解析候选端点。
 *
 * <p>每个模型调用都直接读库（不缓存）：配置只在管理台变更，读库成本相对模型请求
 * 秒级延迟可忽略，且不会出现多实例配置不一致被长期缓存的问题。解密失败或供应商
 * 停用/缺失的端点会被跳过，宁可报“功能未配置”也不会带上残缺密钥调用外部服务。
 *
 * <p>网关启用后功能只能由「功能模型配置」驱动：无配置行、配置被停用或没有任何
 * 可用端点时抛出 {@link FeatureModelNotConfiguredException}（HTTP 400），不允许
 * 客户端回退到环境变量；网关关闭时返回空列表，调用方保持环境变量行为。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelGatewayService implements ModelGateway {

    private final ModelGatewayProperties properties;
    private final FeatureModelConfigMapper configMapper;
    private final ModelProviderMapper providerMapper;
    private final ProviderKeyCipher cipher;

    @Override
    public List<ModelEndpoint> candidates(String featureKey) {
        if (!properties.isEnabled()) {
            return List.of();
        }
        FeatureModelConfig config = configMapper.selectOne(
                new LambdaQueryWrapper<FeatureModelConfig>().eq(FeatureModelConfig::getFeatureKey, featureKey));
        if (config == null || !Boolean.TRUE.equals(config.getEnabled())) {
            throw new FeatureModelNotConfiguredException(featureKey);
        }
        List<ModelEndpoint> endpoints = new ArrayList<>(2);
        addEndpoint(endpoints, config.getPrimaryProviderId(), config.getPrimaryModel());
        addEndpoint(endpoints, config.getFallbackProviderId(), config.getFallbackModel());
        if (endpoints.isEmpty()) {
            throw new FeatureModelNotConfiguredException(featureKey);
        }
        return endpoints;
    }

    private void addEndpoint(List<ModelEndpoint> endpoints, Long providerId, String model) {
        if (providerId == null || !StringUtils.hasText(model)) {
            return;
        }
        ModelProvider provider = providerMapper.selectById(providerId);
        if (provider == null || !Boolean.TRUE.equals(provider.getEnabled())
                || !StringUtils.hasText(provider.getBaseUrl())) {
            return;
        }
        String apiKey;
        try {
            apiKey = cipher.decrypt(provider.getEncryptedApiKey());
        } catch (Exception e) {
            // 密文损坏或密钥轮换后解密失败：跳过该端点，避免携带错误密钥请求外部服务。
            log.warn("model-provider {} API key 解密失败，已跳过该端点", providerId);
            return;
        }
        if (!StringUtils.hasText(apiKey)) {
            return;
        }
        endpoints.add(new ModelEndpoint(provider.getId(),
                trimTrailingSlash(provider.getBaseUrl()), apiKey, model.trim()));
    }

    private String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}