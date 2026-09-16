package com.xander.lab.integration.mcp.juejin.infrastructure;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Encrypted Juejin browser credential owned by one application user. */
@Data
@TableName("juejin_platform_credential")
public class JuejinPlatformCredential {
    @TableId(type = IdType.AUTO) private Long id;
    private Long userId;
    /** AES-GCM 加密的登录凭据包（Cookie 头、x-secsdk-csrf-token、aid、uuid 的 JSON）。 */
    private String encryptedCookie;
    /** 掘金账号展示标识（昵称）。 */
    private String juejinUsername;
    /** ACTIVE=可用凭据；EXPIRED=曾授权但登录态已失效，需重新授权。 */
    private String status;
    private LocalDateTime authorizedAt;
    private LocalDateTime lastValidatedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}