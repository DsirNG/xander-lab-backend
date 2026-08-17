package com.xander.lab.identity.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 微信小程序登录凭据（单行配置：固定 id=1）。
 * AppSecret 在写入与读取时由基础设施层 AES-GCM 加密/解密，实体本身只保存密文。
 */
@Data
@TableName("wechat_credential")
public class WechatCredential {

    /** 固定主键（单行语义：id=1），并发首写冲突由唯一主键兜底 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 微信小程序 AppID，登录时用于 jscode2session。 */
    private String appId;

    /** AES-GCM 密文（明文由 WECHAT_CREDENTIAL_ENCRYPTION_KEY 加密），绝不通过接口回传。 */
    private String encryptedAppSecret;

    /** 最近更新时间（管理台保存时刷新）。 */
    private LocalDateTime updatedAt;
}