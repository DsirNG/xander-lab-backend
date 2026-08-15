package com.xander.lab.admin.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xander.lab.admin.api.FeatureConfigView;
import com.xander.lab.admin.api.UpdateFeatureConfigRequest;
import com.xander.lab.admin.domain.FeatureModelConfig;
import com.xander.lab.admin.domain.ModelProvider;
import com.xander.lab.admin.infrastructure.FeatureModelConfigMapper;
import com.xander.lab.admin.infrastructure.ModelGatewayProperties;
import com.xander.lab.admin.infrastructure.ModelProviderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 功能模型配置管理：为每个功能维护主供应商/主模型与兜底供应商/兜底模型。
 * 配置未落库的功能返回默认空视图；更新是幂等 upsert（feature_key 唯一）。
 */
@Service
@RequiredArgsConstructor
public class FeatureModelConfigService {

    /** 全部功能标识，顺序即管理台展示顺序（与 ModelGateway.FEATURE_* 一致）。 */
    private static final List<String> FEATURES = List.of(
            ModelGateway.FEATURE_AGENT,
            ModelGateway.FEATURE_BLOG_AGENT,
            ModelGateway.FEATURE_BLOG_AGENT_IMAGE,
            ModelGateway.FEATURE_IMG2THREE_VISION);

    private static final int MODEL_MAX = 100;

    private final ModelGatewayProperties properties;
    private final FeatureModelConfigMapper configMapper;
    private final ModelProviderMapper providerMapper;

    public List<FeatureConfigView> listConfigs() {
        requireEnabled();
        List<FeatureModelConfig> rows = configMapper.selectList(null);
        return FEATURES.stream()
                .map(key -> rows.stream()
                        .filter(row -> key.equals(row.getFeatureKey()))
                        .findFirst()
                        .map(this::toView)
                        .orElseGet(() -> new FeatureConfigView(key, false, null, null, null, null, null)))
                .toList();
    }

    @Transactional
    public FeatureConfigView update(String featureKey, UpdateFeatureConfigRequest request) {
        requireEnabled();
        if (!FEATURES.contains(featureKey)) {
            throw new IllegalArgumentException("未知的功能标识: " + featureKey);
        }
        boolean enabled = request.enabled() != null && request.enabled();
        String primaryModel = trimModel(request.primaryModel());
        String fallbackModel = trimModel(request.fallbackModel());
        validateSlot(featureKey, "primary", request.primaryProviderId(), primaryModel);
        validateSlot(featureKey, "fallback", request.fallbackProviderId(), fallbackModel);
        if (!enabled && (request.primaryProviderId() != null || primaryModel != null
                || request.fallbackProviderId() != null || fallbackModel != null)) {
            throw new IllegalArgumentException("功能配置未启用时请清空模型槽位，或先启用该配置");
        }

        FeatureModelConfig existing = configMapper.selectOne(
                new LambdaQueryWrapper<FeatureModelConfig>().eq(FeatureModelConfig::getFeatureKey, featureKey));
        LocalDateTime now = LocalDateTime.now();
        FeatureModelConfig config = existing != null ? existing : new FeatureModelConfig();
        if (existing == null) {
            config.setFeatureKey(featureKey);
        }
        config.setEnabled(enabled);
        config.setPrimaryProviderId(enabled ? request.primaryProviderId() : null);
        config.setPrimaryModel(enabled ? primaryModel : null);
        config.setFallbackProviderId(enabled ? request.fallbackProviderId() : null);
        config.setFallbackModel(enabled ? fallbackModel : null);
        config.setUpdatedAt(now);
        if (existing == null) {
            configMapper.insert(config);
        } else {
            configMapper.updateById(config);
        }
        return listConfigs().stream()
                .filter(view -> featureKey.equals(view.featureKey()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("保存后的功能配置缺失"));
    }

    /**
     * 槽位校验：供应商与模型必须同时填写；供应商必须存在且处于启用状态，
     * 防止把已停用的供应商配置到线上功能。
     */
    private void validateSlot(String featureKey, String slot, Long providerId, String model) {
        boolean providerSet = providerId != null;
        boolean modelSet = model != null;
        if (providerSet != modelSet) {
            throw new IllegalArgumentException(
                    featureKey + " 的 " + slot + " 供应商与模型必须同时填写");
        }
        if (!providerSet) {
            return;
        }
        ModelProvider provider = providerMapper.selectById(providerId);
        if (provider == null || !Boolean.TRUE.equals(provider.getEnabled())) {
            throw new IllegalArgumentException(slot + " 供应商不存在或已停用");
        }
    }

    private String trimModel(String model) {
        if (model == null) return null;
        String trimmed = model.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() > MODEL_MAX) {
            throw new IllegalArgumentException("模型名不能超过 " + MODEL_MAX + " 个字符");
        }
        return trimmed;
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "模型供应商管理尚未启用，请配置 ADMIN_MODEL_GATEWAY_ENABLED=true 并应用数据库迁移");
        }
    }

    private FeatureConfigView toView(FeatureModelConfig config) {
        return new FeatureConfigView(config.getFeatureKey(), Boolean.TRUE.equals(config.getEnabled()),
                config.getPrimaryProviderId(), config.getPrimaryModel(),
                config.getFallbackProviderId(), config.getFallbackModel(),
                config.getUpdatedAt());
    }
}