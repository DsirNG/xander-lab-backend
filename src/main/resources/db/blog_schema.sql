-- ============================================================
-- Xander Lab - Blog Module SQL Schema
-- Database: xander_lab
-- Charset: utf8mb4
-- ============================================================

CREATE DATABASE IF NOT EXISTS `xander_lab`
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE `xander_lab`;

/* 1. 创建系统用户表 */
CREATE TABLE `sys_user` (
                            `id` bigint NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
                            `username` varchar(50) NOT NULL COMMENT '用户名',
                            `password` varchar(100) DEFAULT '123456' COMMENT '密码',
                            `nickname` varchar(50) DEFAULT NULL COMMENT '昵称',
                            `email` varchar(100) DEFAULT NULL COMMENT '邮箱',
                            `avatar` varchar(255) DEFAULT NULL COMMENT '头像URL',
                            `role` varchar(20) DEFAULT 'USER' COMMENT '角色: ADMIN, USER',
                            `status` int DEFAULT '1' COMMENT '状态: 1=正常, 0=禁用',
                            `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                            `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                            UNIQUE KEY `uk_username` (`username`),
                            UNIQUE KEY `uk_email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统用户表';

-- ------------------------------------------------------------
-- 1. 博客分类表 blog_category
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `blog_category` (
  `id`          VARCHAR(64)   NOT NULL COMMENT '分类ID（英文标识，如 frontend）',
  `name`        VARCHAR(64)   NOT NULL COMMENT '分类名称（中文）',
  `sort`        INT           NOT NULL DEFAULT 0 COMMENT '排序权重，越小越靠前',
  `created_at`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='博客分类表';

-- ------------------------------------------------------------
-- 2. 博客文章表 blog_post
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `blog_post` (
  `id`          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '文章ID',
  `title`       VARCHAR(255)  NOT NULL COMMENT '文章标题',
  `summary`     TEXT          NOT NULL COMMENT '文章摘要',
  `content`     LONGTEXT      NOT NULL COMMENT '文章正文（Markdown）',
  `category_id` VARCHAR(64)   NOT NULL COMMENT '分类ID，关联 blog_category.id',
  `author`      VARCHAR(64)   NOT NULL DEFAULT 'Xander' COMMENT '作者',
  `read_time`   VARCHAR(32)   NOT NULL DEFAULT '5 min' COMMENT '预计阅读时间',
  `views`       INT           NOT NULL DEFAULT 0 COMMENT '浏览次数',
  `status`      TINYINT       NOT NULL DEFAULT 1 COMMENT '状态：0=草稿 1=已发布',
  `published_at` DATE         NULL COMMENT '发布日期',
  `created_at`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_category_id` (`category_id`),
  KEY `idx_published_at` (`published_at`),
  KEY `idx_status` (`status`),
  CONSTRAINT `fk_post_category` FOREIGN KEY (`category_id`) REFERENCES `blog_category` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='博客文章表';

-- ------------------------------------------------------------
-- 3. 博客标签表 blog_tag
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `blog_tag` (
  `id`          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '标签ID',
  `name`        VARCHAR(64)   NOT NULL COMMENT '标签名称',
  `created_at`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='博客标签表';

-- ------------------------------------------------------------
-- 4. 文章-标签关联表 blog_post_tag
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `blog_post_tag` (
  `post_id`     BIGINT        NOT NULL COMMENT '文章ID',
  `tag_id`      BIGINT        NOT NULL COMMENT '标签ID',
  PRIMARY KEY (`post_id`, `tag_id`),
  KEY `idx_tag_id` (`tag_id`),
  CONSTRAINT `fk_pt_post` FOREIGN KEY (`post_id`) REFERENCES `blog_post` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_pt_tag`  FOREIGN KEY (`tag_id`)  REFERENCES `blog_tag`  (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文章标签关联表';

-- ============================================================
-- 初始化数据
-- ============================================================

-- 分类数据
INSERT INTO `blog_category` (`id`, `name`, `sort`) VALUES
  ('frontend',     '前端开发', 1),
  ('backend',      '后端技术', 2),
  ('devops',       'DevOps',   3),
  ('architecture', '架构设计', 4),
  ('career',       '职场成长', 5);

-- 标签数据
INSERT INTO `blog_tag` (`name`) VALUES
  ('React'), ('Hooks'), ('原理'),
  ('Performance'), ('Optimization'), ('Web'),
  ('TypeScript'), ('Types'), ('Advanced'),
  ('CSS'), ('Grid'), ('Layout'),
  ('Node.js'), ('Event Loop'), ('Backend'),
  ('Docker'), ('DevOps'), ('Deployment'),
  ('Microfrontend'), ('Architecture'), ('Webpack'),
  ('Career'), ('Growth'), ('Life');

-- 文章数据
INSERT INTO `blog_post` (`id`, `title`, `summary`, `content`, `category_id`, `author`, `read_time`, `views`, `status`, `published_at`) VALUES
(1, '深入理解 React Hooks 原理',
 '本文将深入探讨 React Hooks 的内部实现机制，包括 Fiber 架构、链表结构以及状态更新流程。我们还将讨论在使用 Hooks 时常见陷阱及其解决方案。',
 'React Hooks 是 React 16.8 引入的新特性，它允许你在不编写 class 的情况下使用 state 以及其他的 React 特性。\n\n## Fiber 架构与 Hooks\n\nHooks 的实现严重依赖于 React 的 **Fiber 架构**。每个组件实例对应一个 Fiber 节点，而 Hooks 的状态则是存储在 Fiber 节点的 `memoizedState` 链表中的。\n\n> 💡 Fiber 本质上是一个 JavaScript 对象，它描述了组件树中的一个工作单元。每次渲染时，React 会遍历 Fiber 树来决定需要更新哪些节点。\n\n### useState 的工作流程\n\n当我们调用 `useState` 时，React 内部做了什么？\n\n```javascript\nfunction Counter() {\n  const [count, setCount] = useState(0);\n  const [name, setName] = useState(''Xander'');\n  return (\n    <div>\n      <p>{name} 点击了 {count} 次</p>\n      <button onClick={() => setCount(c => c + 1)}>+1</button>\n    </div>\n  );\n}\n```\n\n## 为什么调用顺序很重要？\n\n因为 React 依赖 Hooks 调用的**顺序**来确定哪个 state 对应哪个 `useState`。\n\n## useEffect 的清理机制\n\n`useEffect` 返回的清理函数会在**下一次 effect 执行前**或**组件卸载时**调用。\n\n## 最佳实践\n\n1. **只在顶层调用 Hooks**\n2. **只在 React 函数中调用 Hooks**\n3. **使用 ESLint 插件**\n4. **合理拆分 state**\n5. **善用自定义 Hook**',
 'frontend', 'Xander', '10 min', 1205, 1, '2026-02-08'),

(2, '前端性能优化实战指南',
 '从网络请求、资源加载、代码执行等多个维度，详细介绍前端性能优化的策略和实践技巧。包含 Web Vitals 指标分析和工具使用。',
 '性能优化是前端开发中不可或缺的一环...',
 'frontend', 'Xander', '15 min', 890, 1, '2026-02-05'),

(3, 'TypeScript 高级类型体操',
 '通过一系列实战案例，讲解 TypeScript 中的高级类型特性，如条件类型、映射类型、模板字面量类型等。适合有一定 TS 基础的开发者。',
 'TypeScript 的类型系统非常强大...',
 'frontend', 'Xander', '20 min', 1500, 1, '2026-02-01'),

(4, 'CSS Grid 布局完全指南',
 '全面解析 CSS Grid 布局的各个属性和概念，助你轻松掌握现代网页布局利器。包含大量图解和实战布局案例。',
 'CSS Grid 是最强大的 CSS 布局系统...',
 'frontend', 'Xander', '12 min', 600, 1, '2026-01-28'),

(5, 'Node.js 事件循环详解',
 '深入剖析 Node.js 事件循环机制，理解宏任务、微任务以及各个阶段的执行顺序。对比浏览器事件循环的异同。',
 '事件循环是 Node.js 异步非阻塞 I/O 的核心...',
 'backend', 'Xander', '18 min', 950, 1, '2026-01-25'),

(6, 'Docker 容器化部署最佳实践',
 '从 Dockerfile 编写优化到多阶段构建，再到 Docker Compose 编排，手把手教你如何高效容器化你的应用。',
 'Docker 改变了软件交付的方式...',
 'devops', 'Xander', '14 min', 780, 1, '2026-01-20'),

(7, '微前端架构设计与落地',
 '探讨微前端架构的几种主流实现方案（qiankun, micro-app, webpack5 module federation），以及在大型项目中的落地经验。',
 '随着前端项目规模的扩大，微前端成为了一种趋势...',
 'architecture', 'Xander', '25 min', 2100, 1, '2026-01-15'),

(8, '程序员的职业规划思考',
 '技术专家还是管理路线？大厂螺丝钉还是创业公司多面手？分享一些关于技术人职业发展的思考和建议。',
 '职业规划是一个持续的过程...',
 'career', 'Xander', '8 min', 3200, 1, '2026-01-10');

-- 文章-标签关联（tag id 对应上面 INSERT 的顺序）
INSERT INTO `blog_post_tag` (`post_id`, `tag_id`) VALUES
  (1, 1),(1, 2),(1, 3),
  (2, 4),(2, 5),(2, 6),
  (3, 7),(3, 8),(3, 9),
  (4, 10),(4, 11),(4, 12),
  (5, 13),(5, 14),(5, 15),
  (6, 16),(6, 17),(6, 18),
  (7, 19),(7, 20),(7, 21),
  (8, 22),(8, 23),(8, 24);
