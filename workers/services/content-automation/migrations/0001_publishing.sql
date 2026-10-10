-- 博客分类：公开博客分类目录及其展示顺序。
CREATE TABLE IF NOT EXISTS blog_category (
  -- 分类稳定标识，兼容现有 category_id。
  id TEXT PRIMARY KEY NOT NULL,
  -- 分类展示名称。
  name TEXT NOT NULL,
  -- 排序权重，数值越小越靠前。
  sort INTEGER NOT NULL DEFAULT 0,
  -- 分类创建时间，使用 UTC ISO-8601 文本。
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  -- 分类最后更新时间，使用 UTC ISO-8601 文本。
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

-- 博客文章：D1 保存元数据和小型正文；较大的正文只保存阿里云 OSS 对象键。
CREATE TABLE IF NOT EXISTS blog_post (
  -- 文章主键，迁移数据时保留现有 MySQL ID。
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  -- 文章标题。
  title TEXT NOT NULL,
  -- 文章摘要。
  summary TEXT NOT NULL,
  -- 正文小于 256 KiB 时内嵌；正文较大时保持 NULL 并使用 content_object_key。
  content_inline TEXT,
  -- 正文在阿里云 OSS 中的对象键；不得存放长期签名 URL。
  content_object_key TEXT,
  -- 分类稳定标识，关联 blog_category.id。
  category_id TEXT NOT NULL REFERENCES blog_category(id),
  -- 封面图 URL，图片本体放在 OSS。
  cover_image TEXT,
  -- 文章作者用户 ID；系统旧文章允许为空。
  user_id INTEGER,
  -- 文章作者的展示名称快照。
  author TEXT NOT NULL DEFAULT 'DinQorAI',
  -- 预计阅读时长展示文本。
  read_time TEXT NOT NULL DEFAULT '5 min',
  -- 文章提示信息，可为空。
  tips TEXT,
  -- 累计阅读次数。
  views INTEGER NOT NULL DEFAULT 0 CHECK (views >= 0),
  -- 生命周期状态：-1=回收站，0=草稿，1=已发布。
  status INTEGER NOT NULL DEFAULT 0 CHECK (status IN (-1, 0, 1)),
  -- 文章发布日期，YYYY-MM-DD；草稿可为空。
  published_at TEXT,
  -- 文章创建时间，使用 UTC ISO-8601 文本。
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  -- 文章最后更新时间，使用 UTC ISO-8601 文本。
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  -- 正文必须恰好保存在 D1 小正文列或 OSS 对象键其中之一。
  CHECK (
    (content_inline IS NOT NULL AND length(CAST(content_inline AS BLOB)) <= 262144 AND content_object_key IS NULL)
    OR (content_inline IS NULL AND content_object_key IS NOT NULL AND length(content_object_key) > 0)
  )
);

-- 博客标签：全站公开标签词表。
CREATE TABLE IF NOT EXISTS blog_tag (
  -- 标签主键，迁移数据时保留现有 MySQL ID。
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  -- 标签名称，全站唯一。
  name TEXT NOT NULL UNIQUE,
  -- 标签创建时间，使用 UTC ISO-8601 文本。
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

-- 文章标签关联：一篇文章可关联多个标签，一个标签可关联多篇文章。
CREATE TABLE IF NOT EXISTS blog_post_tag (
  -- 被标记文章 ID，文章删除时级联删除关联。
  post_id INTEGER NOT NULL REFERENCES blog_post(id) ON DELETE CASCADE,
  -- 关联标签 ID，标签删除时级联删除关联。
  tag_id INTEGER NOT NULL REFERENCES blog_tag(id) ON DELETE CASCADE,
  PRIMARY KEY (post_id, tag_id)
);

-- 多平台发布记录：供公开博客兼容字段查询文章的站外同步状态。
CREATE TABLE IF NOT EXISTS blog_publish_record (
  -- 发布记录主键，迁移数据时保留现有 MySQL ID。
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  -- 发布任务 ID；兼容旧博客文章的记录使用文章 ID 的负值。
  task_id INTEGER NOT NULL,
  -- 记录归属用户 ID。
  user_id INTEGER NOT NULL,
  -- 发布平台标识，如 local、csdn 或 juejin。
  platform TEXT NOT NULL,
  -- 外部平台文章 ID。
  external_id TEXT,
  -- 外部平台文章 URL。
  url TEXT,
  -- 发布状态：PENDING、SUCCEEDED 或 FAILED。
  status TEXT NOT NULL CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
  -- 规范化错误码，可为空。
  error_code TEXT,
  -- 可安全展示的失败原因，可为空。
  error_message TEXT,
  -- 已尝试发布次数。
  attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  -- 成功发布时间，使用 UTC ISO-8601 文本。
  published_at TEXT,
  -- 记录创建时间，使用 UTC ISO-8601 文本。
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  -- 记录最后更新时间，使用 UTC ISO-8601 文本。
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  UNIQUE (task_id, platform)
);

CREATE INDEX IF NOT EXISTS idx_blog_post_public_page
  ON blog_post(status, published_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_blog_post_category_public
  ON blog_post(category_id, status, published_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_blog_post_tag_tag
  ON blog_post_tag(tag_id, post_id);
CREATE INDEX IF NOT EXISTS idx_blog_publish_task_status
  ON blog_publish_record(task_id, platform, status);

INSERT OR IGNORE INTO blog_category (id, name, sort) VALUES
  ('frontend', '前端开发', 1),
  ('backend', '后端技术', 2),
  ('devops', 'DevOps', 3),
  ('architecture', '架构设计', 4),
  ('career', '职场成长', 5);
