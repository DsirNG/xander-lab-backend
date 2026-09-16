-- ============================================================
-- DinQorAI - 微信小程序登录凭据表
-- 线上库需手工执行本文件；baseline 见 wechat_credential_schema.sql（已同步）
-- 凭据由管理台（/api/admin/wechat-credential）维护：AppID 明文 + AppSecret 加密落库
-- ============================================================

CREATE TABLE IF NOT EXISTS `wechat_credential` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `app_id` VARCHAR(100) NOT NULL COMMENT '微信小程序 AppID，管理台录入，登录时用于 jscode2session',
  `encrypted_app_secret` VARCHAR(512) NOT NULL COMMENT 'AES-GCM 加密的 AppSecret（密钥来自 WECHAT_CREDENTIAL_ENCRYPTION_KEY，Base64 32 字节），绝不通过接口回传',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近更新时间（管理台保存时刷新）',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='微信小程序登录凭据（管理台维护，单行 id=1；无数据行或清空时微信登录通道关闭）';
