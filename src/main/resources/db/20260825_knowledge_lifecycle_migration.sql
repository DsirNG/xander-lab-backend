-- 个人知识库补齐编辑与归档能力：知识不再只能新增。生产环境需手工执行本迁移。
-- 归档是可逆的软隐藏（默认列表、到期复习与智能体检索都不再看到），彻底删除走物理删除，不占用本列。
ALTER TABLE `recitation_material`
  ADD COLUMN `archived_at` DATETIME NULL COMMENT '归档时间；非空表示已归档，不参与默认列表与到期复习' AFTER `next_review_at`,
  ADD KEY `idx_recitation_material_user_archived` (`user_id`, `archived_at`, `id`);
