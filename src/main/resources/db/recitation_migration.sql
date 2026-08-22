-- 中文背诵检查：存量部署手工执行本迁移，创建材料与录音尝试表。
CREATE TABLE IF NOT EXISTS `recitation_material` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '背诵材料主键',
  `user_id` BIGINT NOT NULL COMMENT '材料所属用户 sys_user.id',
  `title` VARCHAR(100) NOT NULL COMMENT '用户确认后的材料标题',
  `content` TEXT NOT NULL COMMENT '用户确认后的背诵原文',
  `normalized_content` TEXT NOT NULL COMMENT '去除标点空白后的对齐基准文本',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '材料创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '材料最近更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_recitation_material_user_created` (`user_id`, `created_at`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='中文背诵材料';

CREATE TABLE IF NOT EXISTS `recitation_attempt` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '背诵尝试主键',
  `user_id` BIGINT NOT NULL COMMENT '尝试所属用户 sys_user.id',
  `material_id` BIGINT NOT NULL COMMENT '对应背诵材料 recitation_material.id',
  `audio_url` VARCHAR(1000) NULL COMMENT 'OSS 中的用户录音访问地址',
  `status` VARCHAR(20) NOT NULL COMMENT '任务状态：UPLOADING/PENDING/SUBMITTING/PROCESSING/POLLING/SUCCEEDED/FAILED',
  `provider_task_id` VARCHAR(100) NULL COMMENT '语音供应商返回的异步任务 ID',
  `provider_id` BIGINT NULL COMMENT '实际调用的 model_provider.id，用于继续查询同一远端任务',
  `model` VARCHAR(100) NULL COMMENT '实际使用的音转文模型名',
  `transcript` MEDIUMTEXT NULL COMMENT '语音识别得到的完整文本',
  `result_json` MEDIUMTEXT NULL COMMENT '原文对齐差异与统计结果 JSON',
  `score` DECIMAL(5,2) NULL COMMENT '背诵内容准确度分数，范围 0 至 100',
  `error_message` VARCHAR(500) NULL COMMENT '面向用户的归一化失败原因，不含供应商密钥和响应正文',
  `run_token` VARCHAR(64) NULL COMMENT '多实例工作器认领令牌，防止旧执行覆盖新状态',
  `poll_attempts` INT NOT NULL DEFAULT 0 COMMENT '远端状态查询次数，用于限制异常任务的最长轮询次数',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '背诵尝试创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '任务状态最近更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_recitation_attempt_user_created` (`user_id`, `created_at`, `id`),
  KEY `idx_recitation_attempt_material_created` (`material_id`, `created_at`, `id`),
  KEY `idx_recitation_attempt_status_updated` (`status`, `updated_at`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='中文背诵录音识别与检查任务';
