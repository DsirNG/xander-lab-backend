package com.xander.lab.integration.wechat.infrastructure;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Encrypted per-user credentials for one directly configured official account. */
@Data
@TableName("wechat_official_account_credential")
public class WechatOfficialAccountCredential {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String appId;
    private String encryptedAppSecret;
    private String accountName;
    private String encryptedAccessToken;
    private LocalDateTime accessTokenExpiresAt;
    private String status;
    private LocalDateTime lastValidatedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
