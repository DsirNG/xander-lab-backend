-- 个人知识测验与录音提交幂等：生产环境需手工执行本迁移。
-- 先为历史录音生成稳定值，再改为非空并建立用户级唯一索引，避免已有数据阻断迁移。
ALTER TABLE `recitation_attempt`
  ADD COLUMN `client_request_id` VARCHAR(64) NULL COMMENT '客户端幂等请求标识；同一用户重复提交时复用原任务' AFTER `material_id`;

UPDATE `recitation_attempt`
SET `client_request_id` = CONCAT('legacy-', `id`)
WHERE `client_request_id` IS NULL;

ALTER TABLE `recitation_attempt`
  MODIFY COLUMN `client_request_id` VARCHAR(64) NOT NULL COMMENT '客户端幂等请求标识；同一用户重复提交时复用原任务',
  ADD UNIQUE KEY `uk_recitation_attempt_user_request` (`user_id`, `client_request_id`);

CREATE TABLE `knowledge_quiz_session` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '测验会话主键，由 quiz_knowledge 返回并交给 grade_answer',
  `user_id` BIGINT NOT NULL COMMENT '测验会话所属用户 sys_user.id',
  `material_id` BIGINT NOT NULL COMMENT '本次测验绑定的知识 recitation_material.id',
  `conversation_id` BIGINT NULL COMMENT '发起测验的智能体会话 agent_conversation.id；非对话调用时为空',
  `status` VARCHAR(20) NOT NULL COMMENT '测验会话状态：OPEN等待评分、GRADED已生成唯一评分记录',
  `attempt_id` BIGINT NULL COMMENT '评分完成后对应的 knowledge_quiz_attempt.id',
  `expires_at` DATETIME NOT NULL COMMENT '允许提交评分的截止时间，超过后必须重新出题',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '测验会话创建时间',
  `graded_at` DATETIME NULL COMMENT '首次成功评分时间；重复评分不会刷新',
  PRIMARY KEY (`id`),
  KEY `idx_knowledge_quiz_session_user_created` (`user_id`, `created_at`, `id`),
  KEY `idx_knowledge_quiz_session_status_expires` (`status`, `expires_at`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='个人知识测验会话与幂等状态';

ALTER TABLE `knowledge_quiz_attempt`
  ADD COLUMN `quiz_session_id` BIGINT NULL COMMENT '唯一对应的知识测验会话 knowledge_quiz_session.id' AFTER `material_id`;

-- 历史评分没有可恢复的出题会话，为每条记录补建一个已完成会话并回填关联。
INSERT INTO `knowledge_quiz_session`
  (`user_id`, `material_id`, `conversation_id`, `status`, `attempt_id`, `expires_at`, `created_at`, `graded_at`)
SELECT `user_id`, `material_id`, `conversation_id`, 'GRADED', `id`, `created_at`, `created_at`, `created_at`
FROM `knowledge_quiz_attempt`
WHERE `quiz_session_id` IS NULL;

UPDATE `knowledge_quiz_attempt` attempt
JOIN `knowledge_quiz_session` session ON session.`attempt_id` = attempt.`id`
SET attempt.`quiz_session_id` = session.`id`
WHERE attempt.`quiz_session_id` IS NULL;

ALTER TABLE `knowledge_quiz_attempt`
  MODIFY COLUMN `quiz_session_id` BIGINT NOT NULL COMMENT '唯一对应的知识测验会话 knowledge_quiz_session.id',
  ADD UNIQUE KEY `uk_knowledge_quiz_attempt_session` (`quiz_session_id`);
