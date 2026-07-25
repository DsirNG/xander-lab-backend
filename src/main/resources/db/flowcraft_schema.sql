-- FlowCraft 表结构
-- 由 Spring Boot spring.sql.init 自动执行

CREATE TABLE IF NOT EXISTS `flow_project` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '项目ID',
    `user_id`    BIGINT       NOT NULL                COMMENT '所属用户ID',
    `name`       VARCHAR(255) NOT NULL DEFAULT ''     COMMENT '项目名称',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_updated_at` (`updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='FlowCraft 项目';

CREATE TABLE IF NOT EXISTS `flow_ai_task` (
    `id`          VARCHAR(32)  NOT NULL                COMMENT 'AI 任务ID',
    `user_id`     BIGINT       NOT NULL                COMMENT '所属用户ID',
    `project_id`  VARCHAR(64)  NOT NULL DEFAULT ''     COMMENT '画布项目ID',
    `canvas_id`   VARCHAR(64)  NOT NULL DEFAULT ''     COMMENT '画布ID',
    `node_id`     VARCHAR(128) NOT NULL DEFAULT ''     COMMENT '画布节点ID',
    `type`        VARCHAR(64)  NOT NULL                COMMENT '任务类型，如 text.generate / image.generate',
    `model_id`    VARCHAR(128) NOT NULL DEFAULT ''     COMMENT 'AI 模型ID',
    `status`      VARCHAR(20)  NOT NULL                COMMENT '状态：pending/running/succeeded/failed',
    `progress`    INT          NOT NULL DEFAULT 0      COMMENT '任务进度百分比',
    `input`       LONGTEXT     NOT NULL                COMMENT '请求输入 JSON',
    `output`      LONGTEXT     NOT NULL                COMMENT '结果输出 JSON；图片结果只存 URL',
    `error`       TEXT         NULL                    COMMENT '失败信息',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `started_at`  DATETIME     NULL                    COMMENT '开始执行时间',
    `finished_at` DATETIME     NULL                    COMMENT '结束时间',
    `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    INDEX `idx_flow_ai_task_user_id` (`user_id`),
    INDEX `idx_flow_ai_task_status` (`status`),
    INDEX `idx_flow_ai_task_project_canvas_node` (`project_id`, `canvas_id`, `node_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='FlowCraft AI 生成任务';

CREATE TABLE IF NOT EXISTS `flow_canvas` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '画布ID',
    `project_id` BIGINT       NOT NULL                COMMENT '所属项目ID',
    `name`       VARCHAR(255) NOT NULL DEFAULT '默认画布' COMMENT '画布名称',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    INDEX `idx_project_id` (`project_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='FlowCraft 画布';

CREATE TABLE IF NOT EXISTS `flow_snapshot` (
    `id`         BIGINT   NOT NULL AUTO_INCREMENT COMMENT '快照ID',
    `canvas_id`  BIGINT   NOT NULL                COMMENT '所属画布ID',
    `data`       LONGTEXT NOT NULL                COMMENT '快照JSON数据',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_canvas_id` (`canvas_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='FlowCraft 画布快照';
