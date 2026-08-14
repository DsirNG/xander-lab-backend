-- Content plan: a single recurring topic that triggers headless agent
-- generation and publishing every day at a user-provided local time.
--
-- Timestamps are absolute instants (UTC). trigger_time is the user's local
-- HH:mm in `timezone`. next_run_at is the next absolute trigger instant.
--
-- status:
--   ACTIVE    -> poller may claim it when next_run_at is due
--   RUNNING   -> claimed and being executed, discovered by stale-claim recovery
--   PAUSED    -> user paused, poller skips it
--   CANCELLED -> user cancelled, never scheduled again
--   FINISHED  -> one-shot plan (run_once=1) executed its single trigger successfully
--   FAILED    -> one-shot plan's single run failed, was rejected, or was interrupted
CREATE TABLE IF NOT EXISTS `blog_content_plan` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '内容计划主键',
  `user_id` BIGINT NOT NULL COMMENT '所属用户ID，关联 sys_user.id',
  `topic` VARCHAR(500) NOT NULL COMMENT '每日生成文章的主题',
  `audience` VARCHAR(120) NOT NULL DEFAULT '' COMMENT '目标读者（透传给生成 Agent）',
  `tone` VARCHAR(60) NOT NULL DEFAULT '' COMMENT '文风语气（透传给生成 Agent）',
  `timezone` VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai' COMMENT 'IANA 时区，如 Asia/Shanghai',
  `trigger_time` VARCHAR(5) NOT NULL COMMENT '每日触发本地时刻 HH:mm（单时段，多时段见 trigger_times_json）',
  `trigger_times_json` VARCHAR(500) NULL COMMENT 'JSON 数组：每日多个触发时刻 HH:mm（多时间段模式）',
  `topics_json` TEXT NULL COMMENT 'JSON 数组：多天主题队列，依次使用，最后一个后回到第一个',
  `next_topic_index` INT NULL DEFAULT 0 COMMENT '主题队列下一个待用下标（0 起，循环使用）',
  `sync_csdn` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否同步发布到 CSDN（0 否 / 1 是）',
  `run_once` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '一次性计划：到点只执行一次，执行完自动完结（0 每日循环 / 1 一次性）',
  `next_run_at` TIMESTAMP(3) NULL DEFAULT NULL COMMENT '下次计划触发时间（UTC 绝对时刻；一次性计划完结/失败后为空）',
  `last_run_at` TIMESTAMP(3) NULL COMMENT '最近一次触发时间（UTC）',
  `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '计划状态：ACTIVE/RUNNING/PAUSED/CANCELLED/FINISHED/FAILED（一次性计划执行成功进入 FINISHED，失败进入 FAILED）',
  `error_message` VARCHAR(1000) NULL COMMENT '最近一次执行失败原因',
  `processing_token` CHAR(36) NULL COMMENT '执行认领令牌，防止多实例重复执行',
  `claimed_at` TIMESTAMP(3) NULL COMMENT '执行认领时间（UTC）',
  `deleted_at` TIMESTAMP(3) NULL COMMENT '软删除时间',
  `created_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_blog_plan_user_created` (`user_id`, `created_at`),
  KEY `idx_blog_plan_due` (`status`, `next_run_at`, `id`),
  CONSTRAINT `fk_blog_plan_user`
    FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='定时内容（每日自动生成发布）计划';

-- One durable run row per trigger. plan_id + scheduled_at is the idempotency key:
-- the scheduler never creates a second run for the same trigger instant.
--
-- status transitions:
--   GENERATING -> REVIEWING -> PUBLISHING -> SUCCEEDED
--   any stage -> FAILED
--   REVIEWING -> REVIEW_REJECTED (content failed the compliance gate)
CREATE TABLE IF NOT EXISTS `blog_plan_run` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '计划执行记录主键',
  `plan_id` BIGINT NOT NULL COMMENT '所属计划ID，关联 blog_content_plan.id',
  `scheduled_at` TIMESTAMP(3) NOT NULL COMMENT '计划触发的绝对时刻（UTC）',
  `trigger_type` VARCHAR(16) NOT NULL DEFAULT 'SCHEDULED' COMMENT '触发来源：SCHEDULED（定时）/MANUAL（手动试运行）',
  `status` VARCHAR(16) NOT NULL DEFAULT 'GENERATING' COMMENT '执行状态：GENERATING/REVIEWING/PUBLISHING/SUCCEEDED/FAILED/REVIEW_REJECTED',
  `stage_error` VARCHAR(24) NULL COMMENT '失败阶段（若整体失败，如 generate/review/publish）',
  `error_message` VARCHAR(1000) NULL COMMENT '整体失败原因',
  `agent_task_id` BIGINT NULL COMMENT '关联生成任务 blog_agent_task.id',
  `local_post_id` BIGINT NULL COMMENT '本站发布文章ID，关联 blog_post.id',
  `review_pass` TINYINT(1) NULL COMMENT '合规审核是否通过（NULL=未审核，1=通过，0=未通过）',
  `review_reason` VARCHAR(1000) NULL COMMENT '合规审核不通过理由',
  `csdn_status` VARCHAR(16) NULL COMMENT 'CSDN 发布状态：PENDING/SUCCEEDED/FAILED/SKIPPED/UNSET',
  `csdn_error_code` VARCHAR(64) NULL COMMENT 'CSDN 发布规范化错误码，如 AUTH_REQUIRED',
  `csdn_error_message` VARCHAR(1000) NULL COMMENT 'CSDN 发布失败原因（脱敏）',
  `csdn_external_id` VARCHAR(128) NULL COMMENT 'CSDN 文章 ID',
  `csdn_url` VARCHAR(1000) NULL COMMENT 'CSDN 文章 URL',
  `processing_token` CHAR(36) NULL COMMENT '执行认领令牌，配合并发防重复执行',
  `claimed_at` TIMESTAMP(3) NULL COMMENT '执行认领时间（UTC）',
  `started_at` TIMESTAMP(3) NULL COMMENT '实际开始执行时间（UTC）',
  `finished_at` TIMESTAMP(3) NULL COMMENT '执行结束时间（UTC）',
  `created_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_blog_plan_run_scheduled` (`plan_id`, `scheduled_at`),
  KEY `idx_blog_plan_run_plan_updated` (`plan_id`, `updated_at`),
  KEY `idx_blog_plan_run_due` (`status`, `claimed_at`, `id`),
  CONSTRAINT `fk_blog_plan_run_plan`
    FOREIGN KEY (`plan_id`) REFERENCES `blog_content_plan` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='定时内容计划执行记录';

-- User-facing notifications produced by background plan runs (and extensible
-- to other long-running work). The bell in the Navbar polls/SSE-subscribes
-- this table so that users who are offline during a run still see the outcome.
--
-- type:
--   REVIEW_REJECTED -> generated content failed the compliance gate, reason in message
--   CSDN_FAILED     -> local publish succeeded but CSDN sync failed
--   RUN_FAILED      -> the whole run failed before publishing
--   PLAN_STALLED    -> a run claimed by a crashed instance was recovered into FAILED
CREATE TABLE IF NOT EXISTS `blog_notification` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '通知主键',
  `user_id` BIGINT NOT NULL COMMENT '所属用户ID，关联 sys_user.id',
  `type` VARCHAR(24) NOT NULL COMMENT '通知类型：REVIEW_REJECTED/CSDN_FAILED/RUN_FAILED/PLAN_STALLED',
  `title` VARCHAR(200) NOT NULL COMMENT '通知标题',
  `message` VARCHAR(1000) NOT NULL COMMENT '通知正文（可直接展示）',
  `plan_id` BIGINT NULL COMMENT '关联计划ID，关联 blog_content_plan.id',
  `run_id` BIGINT NULL COMMENT '关联执行记录ID，关联 blog_plan_run.id',
  `is_read` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否已读（0 未读 / 1 已读）',
  `created_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_blog_notification_user_read` (`user_id`, `is_read`, `id`),
  CONSTRAINT `fk_blog_notification_user`
    FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户通知列表（定时任务与长任务结果兜底）';