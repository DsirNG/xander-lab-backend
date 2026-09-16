-- 已执行过 virtual_pay_migration.sql 的环境，仅需执行本增量迁移。
CREATE TABLE IF NOT EXISTS `virtual_pay_settings` (
    `id` bigint NOT NULL COMMENT '固定为1的单行配置主键',
    `enabled` tinyint(1) NOT NULL DEFAULT 0 COMMENT '管理员是否启用虚拟支付',
    `offer_id` varchar(100) NOT NULL DEFAULT '' COMMENT '微信虚拟支付OfferID',
    `encrypted_app_key` varchar(512) DEFAULT NULL COMMENT 'AES-GCM加密的虚拟支付AppKey',
    `plus_product_id` varchar(100) NOT NULL DEFAULT '',
    `plus_price_cents` int NOT NULL DEFAULT 0,
    `plus_duration_days` int NOT NULL DEFAULT 30,
    `pro_product_id` varchar(100) NOT NULL DEFAULT '',
    `pro_price_cents` int NOT NULL DEFAULT 0,
    `pro_duration_days` int NOT NULL DEFAULT 30,
    `ultra_product_id` varchar(100) NOT NULL DEFAULT '',
    `ultra_price_cents` int NOT NULL DEFAULT 0,
    `ultra_duration_days` int NOT NULL DEFAULT 30,
    `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='管理员维护的个人主体虚拟支付配置';
