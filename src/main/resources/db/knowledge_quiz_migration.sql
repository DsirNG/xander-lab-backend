-- 个人知识测验：新部署创建测验会话与评分记录表；已存在旧表时另执行 20260826 迁移。
-- 只新增一张表，不改动 recitation_material：掌握度列在背诵迁移里已经存在，测验复用它们。
CREATE TABLE IF NOT EXISTS `knowledge_quiz_session` (
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

CREATE TABLE IF NOT EXISTS `knowledge_quiz_attempt` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '测验记录主键',
  `user_id` BIGINT NOT NULL COMMENT '测验所属用户 sys_user.id',
  `material_id` BIGINT NOT NULL COMMENT '被测知识 recitation_material.id',
  `quiz_session_id` BIGINT NOT NULL COMMENT '唯一对应的知识测验会话 knowledge_quiz_session.id',
  `conversation_id` BIGINT NULL COMMENT '出题与评分所在的智能体会话 agent_conversation.id，便于回溯评分依据',
  `test_mode` VARCHAR(30) NOT NULL COMMENT '测试方式快照：AI_QA问答、PRACTICE练习题、AUDIO_RECITATION文字抽查',
  `question_count` INT NOT NULL COMMENT '本次出题数量',
  `correct_count` INT NOT NULL COMMENT '完全答对的题数（得分为满分的题）',
  `score` DECIMAL(5,2) NOT NULL COMMENT '按每题得分自动算出的本次得分，范围0至100',
  `verdict` VARCHAR(1000) NULL COMMENT '面向用户的总体讲评',
  `items_json` MEDIUMTEXT NOT NULL COMMENT '逐题证据 JSON：题目、用户原话作答、得分与点评',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '测验记录创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_knowledge_quiz_attempt_session` (`quiz_session_id`),
  KEY `idx_knowledge_quiz_material_created` (`material_id`, `created_at`, `id`),
  KEY `idx_knowledge_quiz_user_created` (`user_id`, `created_at`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='个人知识测验评分记录';
