-- 个人主体小程序虚拟支付：先人工应用本迁移，再开启 WECHAT_VIRTUAL_PAY_ENABLED。
ALTER TABLE `sys_user`
    ADD COLUMN `tier_expires_at` datetime DEFAULT NULL COMMENT '当前订阅权益到期时间，NULL=免费或无到期权益' AFTER `tier`;

ALTER TABLE `sys_user`
    MODIFY COLUMN `tier` varchar(20) NOT NULL DEFAULT 'FREE' COMMENT '订阅等级顺序：FREE、PLUS、PRO、ULTRA';

CREATE TABLE `virtual_pay_order` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '本地订单主键',
    `out_trade_no` varchar(32) NOT NULL COMMENT '开发者业务订单号，8-32位且全局唯一',
    `wx_order_id` varchar(64) DEFAULT NULL COMMENT '微信虚拟支付平台单号，发货幂等键',
    `user_id` bigint NOT NULL COMMENT '购买用户ID，关联sys_user.id',
    `openid` varchar(64) NOT NULL COMMENT '下单时已核验的微信openid',
    `product_id` varchar(64) NOT NULL COMMENT 'MP后台已发布的道具ID',
    `tier` varchar(20) NOT NULL COMMENT '发放订阅等级，顺序为FREE<PLUS<PRO<ULTRA',
    `goods_price` int NOT NULL COMMENT '道具单价，人民币分',
    `quantity` int NOT NULL DEFAULT 1 COMMENT '购买数量，道具直购当前固定为1',
    `duration_days` int NOT NULL COMMENT '成功发货后增加的订阅天数',
    `status` varchar(20) NOT NULL DEFAULT 'PENDING' COMMENT '订单状态：PENDING、DELIVERED、REFUNDED、FAILED',
    `paid_at` datetime DEFAULT NULL COMMENT '微信确认支付时间',
    `delivered_at` datetime DEFAULT NULL COMMENT '订阅权益成功发放时间',
    `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '订单创建时间',
    `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '订单更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_virtual_pay_out_trade_no` (`out_trade_no`),
    UNIQUE KEY `uk_virtual_pay_wx_order_id` (`wx_order_id`),
    KEY `idx_virtual_pay_user_created` (`user_id`, `created_at`),
    KEY `idx_virtual_pay_pending_created` (`status`, `created_at`),
    CONSTRAINT `fk_virtual_pay_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='个人主体小程序虚拟支付订单与订阅发货记录';
