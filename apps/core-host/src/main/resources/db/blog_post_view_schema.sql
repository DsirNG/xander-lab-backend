-- 博客阅读记录表
CREATE TABLE IF NOT EXISTS `blog_post_view` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `post_id` BIGINT NOT NULL COMMENT '文章ID',
    `user_id` BIGINT DEFAULT NULL COMMENT '用户ID（未登录为NULL）',
    `ip_address` VARCHAR(45) DEFAULT NULL COMMENT '客户端IP地址',
    `user_agent` VARCHAR(512) DEFAULT NULL COMMENT '浏览器UA',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '阅读时间',
    PRIMARY KEY (`id`),
    KEY `idx_view_post_id` (`post_id`),
    KEY `idx_view_user_id` (`user_id`),
    KEY `idx_view_ip` (`ip_address`),
    KEY `idx_view_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='博客阅读记录';
