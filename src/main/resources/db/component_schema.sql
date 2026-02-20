-- ============================================================
-- Xander Lab - 组件库 SQL 表结构 (V2 - 支持自增 ID & 动态沙箱)
-- 数据库: xander_lab
-- ============================================================

USE `xander_lab`;

SET FOREIGN_KEY_CHECKS = 0;

-- ------------------------------------------------------------
-- 1. 组件分类表 component_category
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `component_category`;
CREATE TABLE `component_category` (
  `id`             VARCHAR(64)   NOT NULL COMMENT '分类ID (例如: ui-kit)',
  `name_zh`        VARCHAR(64)   NOT NULL COMMENT '分类名称 (中文)',
  `name_en`        VARCHAR(64)   NOT NULL COMMENT '分类名称 (英文)',
  `description_zh` VARCHAR(255)  NULL COMMENT '简短描述 (中文)',
  `description_en` VARCHAR(255)  NULL COMMENT '简短描述 (英文)',
  `sort`           INT           NOT NULL DEFAULT 0 COMMENT '排序权重',
  `created_at`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='组件分类表';

-- ------------------------------------------------------------
-- 2. 组件表 component_item
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `component_item`;
CREATE TABLE `component_item` (
  `id`             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '自增主键ID',
  `category_id`    VARCHAR(64)   NOT NULL COMMENT '关联 component_category.id',
  `title_zh`       VARCHAR(128)  NOT NULL COMMENT '标题 (中文)',
  `title_en`       VARCHAR(128)  NOT NULL COMMENT '标题 (英文)',
  `description_zh` TEXT          NULL COMMENT '描述 (中文)',
  `description_en` TEXT          NULL COMMENT '描述 (英文)',
  `tag_zh`         VARCHAR(64)   NULL COMMENT '标签 (例如: 交互)',
  `tag_en`         VARCHAR(64)   NULL COMMENT '标签 (例如: Interaction)',
  `author`         VARCHAR(64)   NULL COMMENT '作者名称',
  `version`        VARCHAR(32)   NULL COMMENT '版本号 (例如: 1.0.0)',
  `source_code`    LONGTEXT      NULL COMMENT '组件原始代码 (参考/阅读用)',
  `library_code`   LONGTEXT      NULL COMMENT '沙箱底层依赖代码 (Provider/Hooks)',
  `wrapper_code`   LONGTEXT      NULL COMMENT '沙箱环境包裹代码 (HOC/Wrapper)',
  `icon_key`       VARCHAR(64)   NULL COMMENT '图标标识符',
  `sort`           INT           NOT NULL DEFAULT 0 COMMENT '排序权重',
  `status`         TINYINT       NOT NULL DEFAULT 1 COMMENT '状态: 1=启用, 0=审核中, -1=禁用',
  `created_at`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='组件表';

-- ------------------------------------------------------------
-- 3. 组件详情页配置 component_detail_page
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `component_detail_page`;
CREATE TABLE `component_detail_page` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `component_id`   BIGINT       NOT NULL COMMENT '关联组件ID',
  `page_type`      VARCHAR(32)  NOT NULL DEFAULT 'guide' COMMENT '页面类型 (guide, api)',
  `component_key`  VARCHAR(64)  NOT NULL COMMENT 'React页面组件 Key',
  `sort`           INT          NOT NULL DEFAULT 0 COMMENT '排序权重',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='组件详情页配置表';

-- ------------------------------------------------------------
-- 4. 组件演示场景 component_scenario
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `component_scenario`;
CREATE TABLE `component_scenario` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `component_id`   BIGINT       NOT NULL COMMENT '关联组件ID',
  `title_zh`       VARCHAR(128) NOT NULL COMMENT '场景标题 (中文)',
  `title_en`       VARCHAR(128) NOT NULL COMMENT '场景标题 (英文)',
  `description_zh` TEXT         NULL COMMENT '场景描述 (中文)',
  `description_en` TEXT         NULL COMMENT '场景描述 (英文)',
  `code_snippet`   LONGTEXT     NULL COMMENT '展示用的代码片段 (View Code)',
  `demo_key`       VARCHAR(64)  NULL     COMMENT '静态 Demo Key (遗留组件用)',
  `demo_code`      LONGTEXT     NULL     COMMENT '沙箱动态执行的代码',
  `sort`           INT          NOT NULL DEFAULT 0 COMMENT '排序权重',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='组件演示场景表';

SET FOREIGN_KEY_CHECKS = 1;

-- ============================================================
-- 初始数据初始化
-- ============================================================

-- 1. 插入组件分类
INSERT INTO `component_category` (`id`, `name_zh`, `name_en`, `description_zh`, `description_en`, `sort`) VALUES
('ui-kit', '基础组件', 'UI Kit', '用于构建界面的原子组件。', 'Atomic components for building interfaces.', 1),
('feedback', '反馈组件', 'Feedback', '提示与交互反馈类组件。', 'Notifications and interaction feedback.', 2);

-- 2. 插入 Toast 组件 (ID: 1)
INSERT INTO `component_item` 
(`id`, `category_id`, `title_zh`, `title_en`, `description_zh`, `description_en`, `tag_zh`, `tag_en`, `icon_key`, `sort`, `author`, `version`, `status`) VALUES
(1, 'feedback', 'Toast 通知', 'Toast Notifications', '包含物理特性的高级反馈系统。', 'Premium feedback system with physics-based interactions.', '交互', 'Interaction', 'Zap', 1, 'Xander', '1.2.0', 1);

-- 3. 插入 CustomSelect 组件 (ID: 2)
INSERT INTO `component_item` 
(`id`, `category_id`, `title_zh`, `title_en`, `description_zh`, `description_en`, `tag_zh`, `tag_en`, `icon_key`, `sort`, `author`, `version`, `status`) VALUES
(2, 'ui-kit', '自定义选择器', 'Custom Select', '支持自定义样式的标准单选组件。', 'Standard single selection with custom styling capabilities.', '表单', 'Form', 'ChevronsUpDown', 2, 'Xander', '1.0.1', 1);

-- 4. 插入指南页关联
INSERT INTO `component_detail_page` (`component_id`, `page_type`, `component_key`, `sort`) VALUES 
(1, 'guide', 'ToastGuide', 1),
(2, 'guide', 'CustomSelectGuide', 1);

-- 5. 插入 TOAST 全量场景 (ID: 1)
INSERT INTO `component_scenario` (`component_id`, `title_zh`, `title_en`, `description_zh`, `description_en`, `demo_key`, `sort`, `code_snippet`) VALUES
(1, '基础用法 (极简)', 'Basic Usage (Minimal)', '无进度条或关闭按钮的纯净通知。', 'Pure notification state without progress bars or close buttons for a clean, non-intrusive UI.', 'ToastBasicDemo', 1, '// 极简调用示例\ntoast.info("Hello World");'),
(1, '物理交互 (悬停暂停)', 'Interactive Physics (Pause on Hover)', '实时时间锁定：悬停可冻结倒计时。', 'Real-time temporal locking: hovering freezes the countdown, allowing users infinite reading time.', 'ToastHoverDemo', 2, NULL),
(1, '手动关闭', 'Manual Dismissal', '需要确认的显式交互模式。', 'Explicit interaction model showing close buttons for alerts that require acknowledgment.', 'ToastManualDemo', 3, NULL),
(1, 'JSX & 富交互', 'JSX & Rich Actions', '超越字符串：嵌入链接、按钮等。', 'Beyond strings: embed links, buttons, and custom layout logic directly into the feedback stream.', 'ToastActionDemo', 4, NULL),
(1, '不可暂停对比', 'System Comparison (No Pause)', '强制消失的基准测试。', 'A benchmark demo where pauseOnHover is disabled, forcing the notification to disappear regardless of focus.', 'ToastNoHoverDemo', 5, NULL);

-- 6. 插入 CUSTOM SELECT 全量场景 (ID: 2)
INSERT INTO `component_scenario` (`component_id`, `title_zh`, `title_en`, `description_zh`, `description_en`, `demo_key`, `sort`) VALUES
(2, '基础用法', 'Basic Usage', '具备自定义样式的标准单选。', 'Standard single selection with custom styling capabilities.', 'BasicDemo', 1),
(2, '文本对齐', 'Text Alignment', '支持左、中、右对齐。', 'Support for Left, Center, and Right text alignment depending on context.', 'AlignmentDemo', 2),
(2, '交互状态', 'States', '包含错误状态的视觉反馈。', 'Visual feedback for different interaction states including Error.', 'StatusDemo', 3);
