CREATE TABLE IF NOT EXISTS `juejin_category` (
  `category_id` BIGINT NOT NULL COMMENT '掘金侧分类 ID（tag_api/v1/query_category_list 返回的 category_id，天然主键）',
  `category_name` VARCHAR(64) NOT NULL COMMENT '掘金分类名（后端/前端/Android/iOS/人工智能/开发工具/代码人生/阅读）',
  `rank` INT NOT NULL DEFAULT 0 COMMENT 'query_category_list 返回的展示排序（rank 升序）',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次入库时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近一次目录同步时间',
  PRIMARY KEY (`category_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='掘金分类目录（发布时按名称匹配 category_id 的字典，接口同步、幂等入库）';

CREATE TABLE IF NOT EXISTS `juejin_tag` (
  `tag_id` BIGINT NOT NULL COMMENT '掘金侧标签 ID（query_category_list 的 hot_tags 与 query_tag_list 返回的 tag_id，天然主键）',
  `tag_name` VARCHAR(64) NOT NULL COMMENT '掘金标签名（如 前端/后端/JavaScript/AI编程）',
  `category_id` BIGINT NULL COMMENT '所属掘金分类 ID（来自该分类下 hot_tags；query_tag_list 全量列表无归属则 NULL），可空',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次入库时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近一次目录同步时间',
  PRIMARY KEY (`tag_id`),
  KEY `idx_juejin_tag_category` (`category_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='掘金标签目录（发布时按名称匹配 tag_id 的字典，接口同步、幂等入库）';
