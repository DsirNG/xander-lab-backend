package com.xander.lab.admin.infrastructure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 管理台模型网关开关与凭据加密密钥。 */
@Data
@Component
@ConfigurationProperties(prefix = "admin.model-gateway")
public class ModelGatewayProperties {

    /** 总开关；关闭时所有功能走环境变量配置，开启前必须先应用 db 迁移 SQL。 */
    private boolean enabled = false;

    /** Base64 编码的 32 字节 AES 密钥，用于加密 model_provider.api_key 静态存储。 */
    private String encryptionKey;
}