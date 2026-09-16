-- Backfill missing TABLE/COLUMN comments for xander_lab.
-- Safe to re-run. Marker: 20260725_add_missing_comments
--
-- Apply manually against the live database after schema/migration SQL has run.

CREATE TABLE IF NOT EXISTS `xander_schema_migration` (
  `migration_key` VARCHAR(128) NOT NULL COMMENT '迁移标识键',
  `applied_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '迁移执行时间',
  PRIMARY KEY (`migration_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据库迁移执行标记';

START TRANSACTION;

-- ---------------------------------------------------------------------------
-- Table comments (only meaningful if currently empty; re-apply is harmless)
-- ---------------------------------------------------------------------------
ALTER TABLE `blog_agent_message` COMMENT = '博客写作 Agent 会话消息';
ALTER TABLE `blog_agent_source` COMMENT = '博客写作 Agent 参考来源';
ALTER TABLE `blog_agent_task` COMMENT = '博客写作 Agent 任务主表';
ALTER TABLE `blog_agent_version` COMMENT = '博客写作 Agent 文章版本';
ALTER TABLE `email_reminder_task` COMMENT = '定时邮件提醒任务';
ALTER TABLE `flow_ai_task` COMMENT = 'FlowCraft AI 生成任务';
ALTER TABLE `flow_canvas` COMMENT = 'FlowCraft 画布';
ALTER TABLE `flow_project` COMMENT = 'FlowCraft 项目';
ALTER TABLE `flow_snapshot` COMMENT = 'FlowCraft 画布快照';
ALTER TABLE `xander_schema_migration` COMMENT = '数据库迁移执行标记';

-- ---------------------------------------------------------------------------
-- blog_agent_message
-- ---------------------------------------------------------------------------
ALTER TABLE `blog_agent_message`
  MODIFY COLUMN `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `task_id` BIGINT NOT NULL COMMENT '关联 blog_agent_task.id',
  MODIFY COLUMN `role` VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '消息角色：user/assistant/system',
  MODIFY COLUMN `kind` VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '消息类型，如 text/status/review',
  MODIFY COLUMN `stage` VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '所属阶段，如 analyze/draft/review',
  MODIFY COLUMN `content` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '消息正文',
  MODIFY COLUMN `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';

-- ---------------------------------------------------------------------------
-- blog_agent_source
-- ---------------------------------------------------------------------------
ALTER TABLE `blog_agent_source`
  MODIFY COLUMN `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `task_id` BIGINT NOT NULL COMMENT '关联 blog_agent_task.id',
  MODIFY COLUMN `title` VARCHAR(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '来源标题',
  MODIFY COLUMN `url` VARCHAR(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '来源链接',
  MODIFY COLUMN `publisher` VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '发布方/站点',
  MODIFY COLUMN `excerpt` TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '摘录摘要',
  MODIFY COLUMN `reliability` VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '可信度说明',
  MODIFY COLUMN `retrieved_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '抓取/收录时间';

-- ---------------------------------------------------------------------------
-- blog_agent_task
-- ---------------------------------------------------------------------------
ALTER TABLE `blog_agent_task`
  MODIFY COLUMN `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `user_id` BIGINT NOT NULL COMMENT '所属用户ID，关联 sys_user.id',
  MODIFY COLUMN `input` TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '用户原始输入',
  MODIFY COLUMN `input_type` VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'topic' COMMENT '输入类型，如 topic/outline',
  MODIFY COLUMN `audience` VARCHAR(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '目标读者',
  MODIFY COLUMN `tone` VARCHAR(60) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '文风语气',
  MODIFY COLUMN `status` VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'created' COMMENT '任务状态：created/running/succeeded/failed 等',
  MODIFY COLUMN `stage` VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'analyze' COMMENT '当前阶段：analyze/draft/review 等',
  MODIFY COLUMN `title` VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '生成文章标题',
  MODIFY COLUMN `summary` TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '生成文章摘要',
  MODIFY COLUMN `content` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '生成文章正文（Markdown）',
  MODIFY COLUMN `outline` TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '文章大纲',
  MODIFY COLUMN `category_id` VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '拟发布分类ID，关联 blog_category.id',
  MODIFY COLUMN `tags_json` TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '拟发布标签 JSON 数组',
  MODIFY COLUMN `review` TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '审校意见/结果',
  MODIFY COLUMN `illustration_status` VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '插图生成状态',
  MODIFY COLUMN `illustration_error` VARCHAR(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '插图生成失败原因',
  MODIFY COLUMN `published_post_id` BIGINT NULL COMMENT '已发布文章ID，关联 blog_post.id',
  MODIFY COLUMN `error_message` VARCHAR(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '任务失败原因',
  MODIFY COLUMN `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';

-- ---------------------------------------------------------------------------
-- blog_agent_version
-- ---------------------------------------------------------------------------
ALTER TABLE `blog_agent_version`
  MODIFY COLUMN `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `task_id` BIGINT NOT NULL COMMENT '关联 blog_agent_task.id',
  MODIFY COLUMN `version_no` INT NOT NULL COMMENT '版本号（任务内递增）',
  MODIFY COLUMN `title` VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '该版本标题',
  MODIFY COLUMN `summary` TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '该版本摘要',
  MODIFY COLUMN `content` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '该版本正文（Markdown）',
  MODIFY COLUMN `change_note` VARCHAR(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '版本变更说明',
  MODIFY COLUMN `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';

-- ---------------------------------------------------------------------------
-- blog_media_asset
-- ---------------------------------------------------------------------------
ALTER TABLE `blog_media_asset`
  MODIFY COLUMN `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `user_id` BIGINT NOT NULL COMMENT '所属用户ID，关联 sys_user.id',
  MODIFY COLUMN `url` VARCHAR(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '媒体访问地址',
  MODIFY COLUMN `original_name` VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '原始文件名',
  MODIFY COLUMN `stored_name` VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '存储文件名',
  MODIFY COLUMN `size` BIGINT NOT NULL COMMENT '文件大小（字节）',
  MODIFY COLUMN `content_type` VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'MIME 类型',
  MODIFY COLUMN `width` INT NULL COMMENT '图片宽度（像素）',
  MODIFY COLUMN `height` INT NULL COMMENT '图片高度（像素）',
  MODIFY COLUMN `source_type` VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'user_upload' COMMENT '来源类型：user_upload/agent_generate 等',
  MODIFY COLUMN `agent_task_id` BIGINT NULL COMMENT '关联 blog_agent_task.id（AI 生成时）',
  MODIFY COLUMN `generation_meta` TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '生成元数据 JSON',
  MODIFY COLUMN `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';

-- ---------------------------------------------------------------------------
-- component_item (extra live column)
-- ---------------------------------------------------------------------------
ALTER TABLE `component_item`
  MODIFY COLUMN `css_code` TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '组件附加 CSS 代码';

-- ---------------------------------------------------------------------------
-- email_reminder_task
-- ---------------------------------------------------------------------------
ALTER TABLE `email_reminder_task`
  MODIFY COLUMN `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN `user_id` BIGINT NOT NULL COMMENT '所属用户ID，关联 sys_user.id',
  MODIFY COLUMN `client_request_id` VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '客户端幂等请求标识',
  MODIFY COLUMN `recipient_email` VARCHAR(254) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '收件邮箱',
  MODIFY COLUMN `subject` VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '邮件主题',
  MODIFY COLUMN `message` TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '邮件正文（纯文本或 HTML）',
  MODIFY COLUMN `frequency` VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'ONCE' COMMENT '发送频率：ONCE/DAILY/WEEKLY/MONTHLY/CUSTOM',
  MODIFY COLUMN `timezone` VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'Asia/Shanghai' COMMENT 'IANA 时区，如 Asia/Shanghai',
  MODIFY COLUMN `send_time` VARCHAR(5) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '周期发送本地时刻 HH:mm',
  MODIFY COLUMN `recurrence_day` INT NULL COMMENT '周期日：周=ISO 1-7，月=1-31',
  MODIFY COLUMN `interval_days` INT NULL COMMENT '自定义间隔天数',
  MODIFY COLUMN `content_type` VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PLAIN' COMMENT '内容格式：PLAIN/HTML',
  MODIFY COLUMN `template_id` VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'classic' COMMENT '邮件版式模板：classic/minimal/card/notice',
  MODIFY COLUMN `scheduled_at` TIMESTAMP(3) NOT NULL COMMENT '下次计划发送时间（绝对时刻）',
  MODIFY COLUMN `status` VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING' COMMENT '任务状态：PENDING/SENDING/SENT/FAILED/PAUSED 等',
  MODIFY COLUMN `send_attempts` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '已尝试发送次数',
  MODIFY COLUMN `error_message` VARCHAR(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '最近一次失败原因',
  MODIFY COLUMN `processing_token` CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '发送认领令牌，防并发重复发送',
  MODIFY COLUMN `claimed_at` TIMESTAMP(3) NULL DEFAULT NULL COMMENT '认领发送时间',
  MODIFY COLUMN `sent_at` TIMESTAMP(3) NULL DEFAULT NULL COMMENT '最近成功发送时间',
  MODIFY COLUMN `deleted_at` TIMESTAMP(3) NULL DEFAULT NULL COMMENT '软删除时间',
  MODIFY COLUMN `created_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  MODIFY COLUMN `updated_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间';

-- ---------------------------------------------------------------------------
-- flow_ai_task
-- ---------------------------------------------------------------------------
ALTER TABLE `flow_ai_task`
  MODIFY COLUMN `id` VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'AI 任务ID',
  MODIFY COLUMN `user_id` BIGINT NOT NULL COMMENT '所属用户ID',
  MODIFY COLUMN `project_id` VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '画布项目ID',
  MODIFY COLUMN `canvas_id` VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '画布ID',
  MODIFY COLUMN `node_id` VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '画布节点ID',
  MODIFY COLUMN `type` VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '任务类型，如 text.generate / image.generate',
  MODIFY COLUMN `model_id` VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT 'AI 模型ID',
  MODIFY COLUMN `status` VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '状态：pending/running/succeeded/failed',
  MODIFY COLUMN `progress` INT NOT NULL DEFAULT 0 COMMENT '任务进度百分比',
  MODIFY COLUMN `input` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '请求输入 JSON',
  MODIFY COLUMN `output` LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '结果输出 JSON；图片结果只存 URL',
  MODIFY COLUMN `error` TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '失败信息',
  MODIFY COLUMN `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `started_at` DATETIME NULL COMMENT '开始执行时间',
  MODIFY COLUMN `finished_at` DATETIME NULL COMMENT '结束时间',
  MODIFY COLUMN `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';

-- ---------------------------------------------------------------------------
-- flow_* timestamps
-- ---------------------------------------------------------------------------
ALTER TABLE `flow_canvas`
  MODIFY COLUMN `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';

ALTER TABLE `flow_project`
  MODIFY COLUMN `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';

ALTER TABLE `flow_snapshot`
  MODIFY COLUMN `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';

-- ---------------------------------------------------------------------------
-- xander_schema_migration
-- ---------------------------------------------------------------------------
ALTER TABLE `xander_schema_migration`
  MODIFY COLUMN `migration_key` VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '迁移标识键',
  MODIFY COLUMN `applied_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '迁移执行时间';

INSERT IGNORE INTO `xander_schema_migration` (`migration_key`)
VALUES ('20260725_add_missing_comments');

COMMIT;
