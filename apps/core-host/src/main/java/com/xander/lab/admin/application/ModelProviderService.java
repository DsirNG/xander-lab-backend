package com.xander.lab.admin.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xander.lab.admin.api.CreateProviderRequest;
import com.xander.lab.admin.api.ModelProviderView;
import com.xander.lab.admin.api.UpdateProviderRequest;
import com.xander.lab.admin.domain.FeatureModelConfig;
import com.xander.lab.admin.domain.ModelProvider;
import com.xander.lab.admin.infrastructure.FeatureModelConfigMapper;
import com.xander.lab.admin.infrastructure.ModelGatewayProperties;
import com.xander.lab.admin.infrastructure.ModelProviderMapper;
import com.xander.lab.admin.infrastructure.ProviderKeyCipher;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 模型供应商管理：创建/更新/停用/删除，API 密钥加密落库且永不下发。
 * 删除前检查是否仍被功能配置引用，被引用时拒绝删除（409）防止静默失效。
 */
@Service
@RequiredArgsConstructor
public class ModelProviderService {

    private static final String VENDOR_OPENAI_COMPATIBLE = "OPENAI_COMPATIBLE";
    private static final int NAME_MAX = 100;
    private static final int BASE_URL_MAX = 255;
    private static final int DESCRIPTION_MAX = 500;

    private final ModelGatewayProperties properties;
    private final ModelProviderMapper mapper;
    private final FeatureModelConfigMapper configMapper;
    private final ProviderKeyCipher cipher;

    public List<ModelProviderView> list() {
        requireEnabled();
        return mapper.selectList(new LambdaQueryWrapper<ModelProvider>()
                        .orderByAsc(ModelProvider::getPriority)
                        .orderByAsc(ModelProvider::getId))
                .stream().map(this::toView).toList();
    }

    @Transactional
    public ModelProviderView create(CreateProviderRequest request) {
        requireEnabled();
        validateCommonFields(request.name(), request.vendor(), request.baseUrl(), request.description());
        if (!StringUtils.hasText(request.apiKey())) {
            throw new IllegalArgumentException("API 密钥不能为空");
        }
        if (mapper.selectCount(new LambdaQueryWrapper<ModelProvider>()
                .eq(ModelProvider::getName, normalize(request.name()))) > 0) {
            throw new IllegalArgumentException("供应商名称已存在");
        }
        ModelProvider provider = new ModelProvider();
        provider.setName(normalize(request.name()));
        provider.setVendor(normalizeVendor(request.vendor()));
        provider.setBaseUrl(normalize(request.baseUrl()));
        provider.setEncryptedApiKey(cipher.encrypt(request.apiKey().trim()));
        provider.setDescription(trimToNull(request.description()));
        provider.setEnabled(request.enabled() == null || request.enabled());
        provider.setPriority(request.priority() == null ? 0 : request.priority());
        mapper.insert(provider);
        return toView(mapper.selectById(provider.getId()));
    }

    @Transactional
    public ModelProviderView update(Long id, UpdateProviderRequest request) {
        requireEnabled();
        ModelProvider provider = requireProvider(id);
        validateCommonFields(request.name(), request.vendor(), request.baseUrl(), request.description());
        Long duplicate = mapper.selectList(new LambdaQueryWrapper<ModelProvider>()
                        .eq(ModelProvider::getName, normalize(request.name())))
                .stream().map(ModelProvider::getId)
                .filter(existing -> !existing.equals(id))
                .findFirst().orElse(null);
        if (duplicate != null) {
            throw new IllegalArgumentException("供应商名称已存在");
        }
        provider.setName(normalize(request.name()));
        provider.setVendor(normalizeVendor(request.vendor()));
        provider.setBaseUrl(normalize(request.baseUrl()));
        // 密钥留空表示保持原值，避免更新其它字段时误覆盖已加密密钥。
        if (StringUtils.hasText(request.apiKey())) {
            provider.setEncryptedApiKey(cipher.encrypt(request.apiKey().trim()));
        }
        provider.setDescription(trimToNull(request.description()));
        provider.setEnabled(request.enabled() == null || request.enabled());
        provider.setPriority(request.priority() == null ? 0 : request.priority());
        mapper.updateById(provider);
        return toView(mapper.selectById(id));
    }

    @Transactional
    public void setEnabled(Long id, boolean enabled) {
        requireEnabled();
        ModelProvider provider = requireProvider(id);
        if (provider.getEnabled() != null && provider.getEnabled() == enabled) {
            return;
        }
        ModelProvider update = new ModelProvider();
        update.setId(id);
        update.setEnabled(enabled);
        mapper.updateById(update);
    }

    @Transactional
    public void delete(Long id) {
        requireEnabled();
        requireProvider(id);
        if (configMapper.selectCount(new LambdaQueryWrapper<FeatureModelConfig>()
                .and(q -> q.eq(FeatureModelConfig::getPrimaryProviderId, id)
                        .or().eq(FeatureModelConfig::getFallbackProviderId, id))) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "该供应商仍被功能模型配置引用，请先移除相关配置再删除");
        }
        mapper.deleteById(id);
    }

    private ModelProvider requireProvider(Long id) {
        ModelProvider provider = mapper.selectById(id);
        if (provider == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "供应商不存在");
        }
        return provider;
    }

    private void validateCommonFields(String name, String vendor, String baseUrl, String description) {
        if (!StringUtils.hasText(name) || normalize(name).length() > NAME_MAX) {
            throw new IllegalArgumentException("供应商名称不能为空且不超过 " + NAME_MAX + " 个字符");
        }
        if (!StringUtils.hasText(vendor)) {
            throw new IllegalArgumentException("协议类型不能为空");
        }
        if (!StringUtils.hasText(baseUrl)
                || (!baseUrl.trim().startsWith("http://") && !baseUrl.trim().startsWith("https://"))) {
            throw new IllegalArgumentException("API 基础地址必须以 http(s):// 开头");
        }
        if (baseUrl.trim().length() > BASE_URL_MAX) {
            throw new IllegalArgumentException("API 基础地址过长");
        }
        if (description != null && description.trim().length() > DESCRIPTION_MAX) {
            throw new IllegalArgumentException("备注不能超过 " + DESCRIPTION_MAX + " 个字符");
        }
    }

    private String normalizeVendor(String vendor) {
        return VENDOR_OPENAI_COMPATIBLE.equalsIgnoreCase(vendor.trim())
                ? VENDOR_OPENAI_COMPATIBLE : vendor.trim();
    }

    private String normalize(String value) {
        return value.trim();
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "模型供应商管理尚未启用，请配置 ADMIN_MODEL_GATEWAY_ENABLED=true 并应用数据库迁移");
        }
    }

    private ModelProviderView toView(ModelProvider provider) {
        return new ModelProviderView(provider.getId(), provider.getName(), provider.getVendor(),
                provider.getBaseUrl(), StringUtils.hasText(provider.getEncryptedApiKey()),
                provider.getDescription(),
                Boolean.TRUE.equals(provider.getEnabled()),
                provider.getPriority() == null ? 0 : provider.getPriority(),
                provider.getCreatedAt(), provider.getUpdatedAt());
    }
}
