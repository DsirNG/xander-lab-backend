-- 将现有背诵材料扩展为个人知识镜像；生产环境需手工执行本迁移。
ALTER TABLE `recitation_material`
  ADD COLUMN `knowledge_type` VARCHAR(30) NOT NULL DEFAULT 'RECITATION' COMMENT '知识类型：RECITATION背诵、CONCEPT概念、MATH数学' AFTER `normalized_content`,
  ADD COLUMN `test_mode` VARCHAR(30) NOT NULL DEFAULT 'AUDIO_RECITATION' COMMENT '测试方式：录音背诵、AI问答或练习题' AFTER `knowledge_type`,
  ADD COLUMN `mastery_score` DECIMAL(5,2) NOT NULL DEFAULT 0 COMMENT '最近一次有效测试的掌握分数，范围0至100' AFTER `test_mode`,
  ADD COLUMN `mastery_level` VARCHAR(20) NOT NULL DEFAULT 'NEW' COMMENT '掌握等级：NEW、FAMILIAR、RECALLING、APPLYING、MASTERED' AFTER `mastery_score`,
  ADD COLUMN `review_count` INT NOT NULL DEFAULT 0 COMMENT '已经完成的有效测试次数' AFTER `mastery_level`,
  ADD COLUMN `last_review_at` DATETIME NULL COMMENT '最近一次完成有效测试的时间' AFTER `review_count`,
  ADD COLUMN `next_review_at` DATETIME NULL COMMENT '依据掌握程度计算的建议下次复习时间' AFTER `last_review_at`,
  ADD KEY `idx_recitation_material_user_review` (`user_id`, `next_review_at`, `mastery_level`);
