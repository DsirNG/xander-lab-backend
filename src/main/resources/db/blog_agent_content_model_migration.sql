-- Run once for databases where blog_agent_schema.sql was already applied.
ALTER TABLE `blog_agent_task`
  ADD COLUMN `content_boundary` TEXT NULL COMMENT '用户主线、直接扩展与范围边界' AFTER `outline`,
  ADD COLUMN `knowledge_graph_json` LONGTEXT NULL COMMENT '按需生成的知识关系图谱' AFTER `content_boundary`;
