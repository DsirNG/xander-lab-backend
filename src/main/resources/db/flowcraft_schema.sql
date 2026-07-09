-- FlowCraft 表结构
-- 由 Spring Boot spring.sql.init 自动执行

CREATE TABLE IF NOT EXISTS `flow_project` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '项目ID',
    `user_id`    BIGINT       NOT NULL                COMMENT '所属用户ID',
    `name`       VARCHAR(255) NOT NULL DEFAULT ''     COMMENT '项目名称',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_updated_at` (`updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `flow_canvas` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '画布ID',
    `project_id` BIGINT       NOT NULL                COMMENT '所属项目ID',
    `name`       VARCHAR(255) NOT NULL DEFAULT '默认画布' COMMENT '画布名称',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    INDEX `idx_project_id` (`project_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `flow_snapshot` (
    `id`         BIGINT   NOT NULL AUTO_INCREMENT COMMENT '快照ID',
    `canvas_id`  BIGINT   NOT NULL                COMMENT '所属画布ID',
    `data`       LONGTEXT NOT NULL                COMMENT '快照JSON数据',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_canvas_id` (`canvas_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
