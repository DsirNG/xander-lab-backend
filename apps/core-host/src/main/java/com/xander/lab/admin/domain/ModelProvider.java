package com.xander.lab.admin.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 模型供应商（OpenAI 兼容网关）。
 * apiKey 在写入与读取时由基础设施层 AES-GCM 加密/解密，实体本身只保存密文。
 */
@Data
@TableName("model_provider")
public class ModelProvider {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 供应商显示名称，全局唯一（uk_model_provider_name）。 */
    private String name;

    /** 协议类型：OPENAI_COMPATIBLE=OpenAI 兼容 Responses/Images 网关。 */
    private String vendor;

    /** OpenAI 兼容 API 基础地址，如 https://api.openai.com/v1。 */
    private String baseUrl;

    /** AES-GCM 密文（明文由 MODEL_PROVIDER_ENCRYPTION_KEY 加密），绝不通过接口回传。 */
    private String encryptedApiKey;

    /** 供应商备注，仅管理台展示。 */
    private String description;

    /** true=启用，false=停用；停用的供应商不会被任何功能选中。 */
    private Boolean enabled;

    /** 管理台展示排序，数字越小越靠前。 */
    private Integer priority;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}