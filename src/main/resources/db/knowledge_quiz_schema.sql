-- 个人知识测验：概念问答与练习题的评分留档基线表。
-- 与 recitation_attempt 分开建表：那张表的列（audio_url/provider_task_id/transcript/run_token/poll_attempts）
-- 全是语音识别异步任务专用的，测验只需要题目、作答与评分证据。

CREATE TABLE IF NOT EXISTS `knowledge_quiz_attempt` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '测验记录主键',
  `user_id` BIGINT NOT NULL COMMENT '测验所属用户 sys_user.id',
  `material_id` BIGINT NOT NULL COMMENT '被测知识 recitation_material.id',
  `conversation_id` BIGINT NULL COMMENT '出题与评分所在的智能体会话 agent_conversation.id，便于回溯评分依据',
  `test_mode` VARCHAR(30) NOT NULL COMMENT '测试方式快照：AI_QA问答、PRACTICE练习题、AUDIO_RECITATION文字抽查',
  `question_count` INT NOT NULL COMMENT '本次出题数量',
  `correct_count` INT NOT NULL COMMENT '完全答对的题数（得分为满分的题）',
  `score` DECIMAL(5,2) NOT NULL COMMENT '按每题得分自动算出的本次得分，范围0至100',
  `verdict` VARCHAR(1000) NULL COMMENT '面向用户的总体讲评',
  `items_json` MEDIUMTEXT NOT NULL COMMENT '逐题证据 JSON：题目、用户原话作答、得分与点评',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '测验记录创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_knowledge_quiz_material_created` (`material_id`, `created_at`, `id`),
  KEY `idx_knowledge_quiz_user_created` (`user_id`, `created_at`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='个人知识测验评分记录';
