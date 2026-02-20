-- ============================================================
-- Xander Lab - 组件库 SQL 表结构
-- 数据库: xander_lab
-- ============================================================

USE `xander_lab`;

-- ------------------------------------------------------------
-- 1. 组件分类表 component_category (例如：UI Kit, Data Entry, Feedback)
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `component_category` (
  `id`          VARCHAR(64)   NOT NULL COMMENT '分类ID (例如: ui-kit)',
  `name_zh`     VARCHAR(64)   NOT NULL COMMENT '分类名称 (中文)',
  `name_en`     VARCHAR(64)   NOT NULL COMMENT '分类名称 (英文)',
  `description_zh` VARCHAR(255) NULL COMMENT '简短描述 (中文)',
  `description_en` VARCHAR(255) NULL COMMENT '简短描述 (英文)',
  `sort`        INT           NOT NULL DEFAULT 0 COMMENT '排序权重',
  `created_at`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='组件分类表';

-- ------------------------------------------------------------
-- 2. 组件表 component_item (例如：Toast, CustomSelect)
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `component_item` (
  `id`          VARCHAR(64)   NOT NULL COMMENT '组件ID (例如: toast)',
  `category_id` VARCHAR(64)   NOT NULL COMMENT '关联 component_category.id',
  `title_zh`    VARCHAR(128)  NOT NULL COMMENT '标题 (中文)',
  `title_en`    VARCHAR(128)  NOT NULL COMMENT '标题 (英文)',
  `description_zh` TEXT       NULL COMMENT '描述 (中文)',
  `description_en` TEXT       NULL COMMENT '描述 (英文)',
  `tag_zh`      VARCHAR(64)   NULL COMMENT '标签 (例如: 交互)',
  `tag_en`      VARCHAR(64)   NULL COMMENT '标签 (例如: Interaction)',
  `author`      VARCHAR(64)   NULL COMMENT '作者名称',
  `version`     VARCHAR(32)   NULL COMMENT '版本号 (例如: 1.0.0)',
  `icon_key`    VARCHAR(64)   NULL COMMENT '图标标识符 (例如: Activity, ChevronsUpDown)',
  `sort`        INT           NOT NULL DEFAULT 0 COMMENT '排序权重',
  `status`      TINYINT       NOT NULL DEFAULT 1 COMMENT '状态: 1=启用, 0=禁用',
  `created_at`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_comp_category` FOREIGN KEY (`category_id`) REFERENCES `component_category` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='组件表';

-- ------------------------------------------------------------
-- 3. 组件详情页配置 component_detail_page (例如：指南/API文档)
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `component_detail_page` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `component_id`   VARCHAR(64)  NOT NULL COMMENT '关联组件ID',
  `page_type`      VARCHAR(32)  NOT NULL DEFAULT 'guide' COMMENT '页面类型 (guide, api 等)',
  `component_key`  VARCHAR(64)  NOT NULL COMMENT 'React组件键名 (例如: CustomSelectGuide)',
  `sort`           INT          NOT NULL DEFAULT 0 COMMENT '排序权重',
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_detail_comp` FOREIGN KEY (`component_id`) REFERENCES `component_item` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='组件详情页配置表';

-- ------------------------------------------------------------
-- 4. 组件演示场景 component_scenario (例如：基础用法, 对齐方式)
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `component_scenario` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `component_id`   VARCHAR(64)  NOT NULL COMMENT '关联组件ID',
  `title_zh`       VARCHAR(128) NOT NULL COMMENT '场景标题 (中文)',
  `title_en`       VARCHAR(128) NOT NULL COMMENT '场景标题 (英文)',
  `description_zh` TEXT         NULL COMMENT '场景描述 (中文)',
  `description_en` TEXT         NULL COMMENT '场景描述 (英文)',
  `code_snippet`   LONGTEXT     NULL COMMENT '用于 "View Code" 按钮展示的源代码片段（格式化/精简版）',
  `demo_key`       VARCHAR(64)  NULL     COMMENT 'React演示组件键名 (例如: ToastBasicDemo)；与 demo_code 二选一',
  `demo_code`      LONGTEXT     NULL     COMMENT '可直接运行的 JSX demo 代码；有此字段时无需 demo_key',
  `sort`           INT          NOT NULL DEFAULT 0 COMMENT '排序权重',
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_scenario_comp` FOREIGN KEY (`component_id`) REFERENCES `component_item` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='组件演示场景表';

-- ============================================================
-- 初始化数据 (基于现有 constants.jsx)
-- ============================================================

-- 1. 插入分类
INSERT INTO `component_category` (`id`, `name_zh`, `name_en`, `description_zh`, `description_en`, `sort`) VALUES
('ui-kit', 'UI 组件库', 'UI Kit', '用于构建一致界面的原子组件。', 'Atomic components for building consistent interfaces.', 1);

-- 2. 插入组件
INSERT INTO `component_item` 
(`id`, `category_id`, `title_zh`, `title_en`, `description_zh`, `description_en`, `tag_zh`, `tag_en`, `icon_key`, `sort`, `author`, `version`) VALUES
('toast', 'ui-kit', 'Toast 通知', 'Toast Notifications', '包含物理特性的高级反馈系统。', 'Premium feedback system with physics-based interactions.', '交互', 'Interaction', 'Activity', 1, 'Xander', '1.2.0'),
('custom-select', 'ui-kit', '自定义选择器', 'Custom Select', '支持自定义样式的标准单选组件。', 'Standard single selection with custom styling capabilities.', '表单', 'Form', 'ChevronsUpDown', 2, 'Xander', '1.0.1');

-- 3. 插入详情页配置
INSERT INTO `component_detail_page` (`component_id`, `page_type`, `component_key`) VALUES 
('toast', 'guide', 'ToastGuide'),
('custom-select', 'guide', 'CustomSelectGuide');

-- 4. 插入 TOAST 演示场景
-- 注意：为了保持简洁，此处代码片段已简化，实际生产环境中应包含完整代码
INSERT INTO `component_scenario` (`component_id`, `title_zh`, `title_en`, `description_zh`, `description_en`, `demo_key`, `sort`, `code_snippet`) VALUES
('toast', '基础用法 (极简)', 'Basic Usage (Minimal)', '无进度条或关闭按钮的纯净通知。', 'Pure notification state without progress bars or close buttons for a clean, non-intrusive UI.', 'ToastBasicDemo', 1, 'export const ToastBasicDemo = () => { ... }'),
('toast', '物理交互 (悬停暂停)', 'Interactive Physics (Pause on Hover)', '实时时间锁定：悬停可冻结倒计时。', 'Real-time temporal locking: hovering freezes the countdown, allowing users infinite reading time.', 'ToastHoverDemo', 2, 'export const ToastHoverDemo = () => { ... }'),
('toast', '手动关闭', 'Manual Dismissal', '需要确认的显式交互模式。', 'Explicit interaction model showing close buttons for alerts that require acknowledgment.', 'ToastManualDemo', 3, 'export const ToastManualDemo = () => { ... }'),
('toast', 'JSX & 富交互', 'JSX & Rich Actions', '超越字符串：嵌入链接、按钮等。', 'Beyond strings: embed links, buttons, and custom layout logic directly into the feedback stream.', 'ToastActionDemo', 4, 'export const ToastActionDemo = () => { ... }'),
('toast', '不可暂停对比', 'System Comparison (No Pause)', '强制消失的基准测试。', 'A benchmark demo where pauseOnHover is disabled, forcing the notification to disappear regardless of focus.', 'ToastNoHoverDemo', 5, 'export const ToastNoHoverDemo = () => { ... }');

-- 5. 插入 CUSTOM SELECT 演示场景
INSERT INTO `component_scenario` (`component_id`, `title_zh`, `title_en`, `description_zh`, `description_en`, `demo_key`, `sort`, `code_snippet`) VALUES
('custom-select', '基础用法', 'Basic Usage', '具备自定义样式的标准单选。', 'Standard single selection with custom styling capabilities.', 'BasicDemo', 1, NULL),
('custom-select', '文本对齐', 'Text Alignment', '支持左、中、右对齐。', 'Support for Left, Center, and Right text alignment depending on context.', 'AlignmentDemo', 2, NULL),
('custom-select', '交互状态', 'States', '包含错误状态的视觉反馈。', 'Visual feedback for different interaction states including Error.', 'StatusDemo', 3, '<CustomSelect ... error="This field is required" />');

-- ============================================================
-- 迁移脚本：用于已存在的数据库（首次执行 schema 时不需要）
-- 执行前请确认 demo_key 列当前确为 NOT NULL，且 demo_code 列不存在
-- ============================================================
-- ALTER TABLE `component_scenario`
--   MODIFY COLUMN `demo_key` VARCHAR(64) NULL COMMENT 'React演示组件键名；与 demo_code 二选一',
--   ADD COLUMN `demo_code` LONGTEXT NULL COMMENT '可直接运行的 JSX demo 代码' AFTER `demo_key`;
