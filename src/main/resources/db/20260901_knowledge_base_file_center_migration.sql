-- 知识库文件中心：存量环境需手工执行本迁移（新库直接用 knowledge_base_schema.sql）。
-- 迁移标记键：20260901_knowledge_base_file_center
-- 内容分两部分：
--   1. 新建 knowledgebase 模块自有的 9 张表（文件夹、标签、文件、文件正文、文件标签、上传会话、上传分片、章节、结构图节点）。
--   2. 扩展共享通知表 blog_notification：加一个与业务无关的资源引用二元组，
--      让“大部头未自动生成结构图”这类通知能指回具体文件，而不必挪用 plan_id/run_id 这两个计划专用列。
-- 依赖顺序：blog_content_plan_schema.sql（或 blog_content_plan_migration.sql）必须已建出 blog_notification。

-- ── 1. 知识库文件中心自有表 ─────────────────────────────────────────

CREATE TABLE `knowledge_folder` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '文件夹主键',
  `user_id` BIGINT NOT NULL COMMENT '文件夹所属用户 sys_user.id；所有查询都必须带该条件',
  `parent_id` BIGINT NOT NULL DEFAULT 0 COMMENT '父文件夹 knowledge_folder.id；0 表示根目录下的一级文件夹。用 0 而非 NULL 是为了让同级同名唯一索引生效（MySQL 唯一索引不约束 NULL）',
  `name` VARCHAR(120) NOT NULL COMMENT '文件夹显示名，同一父目录下不允许重名',
  `path` VARCHAR(1000) NOT NULL COMMENT '物化祖先路径，形如 /3/17/ 且以自身 id 结尾；用于子树查询与移动时的环检测，避免递归查询',
  `depth` INT NOT NULL DEFAULT 1 COMMENT '层级深度，根目录下的一级文件夹为 1；受 knowledge-base.max-folder-depth 限制',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_knowledge_folder_sibling_name` (`user_id`, `parent_id`, `name`),
  KEY `idx_knowledge_folder_user_parent` (`user_id`, `parent_id`, `id`),
  KEY `idx_knowledge_folder_user_path` (`user_id`, `path`(255))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库文件夹树（根目录只放文件夹，不放文件）';

CREATE TABLE `knowledge_tag` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '标签主键',
  `user_id` BIGINT NOT NULL COMMENT '标签所属用户 sys_user.id；标签是用户私有词表，不跨用户共享',
  `name` VARCHAR(60) NOT NULL COMMENT '标签名，同一用户下唯一（大小写与首尾空格已归一化后比较）',
  `color` VARCHAR(20) NULL COMMENT '前端展示色值，形如 #2F6FED；为空由前端取默认色',
  `description` VARCHAR(200) NULL COMMENT '标签说明，便于用户回忆这个标签当初的用途',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_knowledge_tag_user_name` (`user_id`, `name`),
  KEY `idx_knowledge_tag_user_id` (`user_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库标签词表（用户私有）';

CREATE TABLE `knowledge_file` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '文件主键',
  `user_id` BIGINT NOT NULL COMMENT '文件所属用户 sys_user.id',
  `folder_id` BIGINT NOT NULL COMMENT '所在文件夹 knowledge_folder.id；必须大于 0——根目录不允许直接放文件',
  `original_name` VARCHAR(255) NOT NULL COMMENT '上传时的原始文件名（已过滤路径分隔符）',
  `display_name` VARCHAR(255) NOT NULL COMMENT '列表展示名，可重命名；同一文件夹下不强制唯一',
  `extension` VARCHAR(20) NOT NULL DEFAULT '' COMMENT '小写扩展名，不含点；用于筛选与判断能否抽取正文',
  `content_type` VARCHAR(120) NULL COMMENT '上传声明的 MIME 类型，仅作展示与下载头，不作为安全判据',
  `size_bytes` BIGINT NOT NULL COMMENT '文件字节数',
  `file_hash` CHAR(64) NOT NULL COMMENT '文件内容 SHA-256 十六进制值；分片合并后由服务端重算并与客户端声明值比对',
  `storage_url` VARCHAR(500) NOT NULL COMMENT '对象存储访问地址；删除文件时按该地址尽力回收存储对象',
  `text_extracted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否成功抽取出纯文本正文（0 未抽取/不支持，1 已抽取）；未抽取的文件无法识别章节',
  `char_count` INT NOT NULL DEFAULT 0 COMMENT '抽取出的正文字符数，0 表示没有正文',
  `chapter_count` INT NOT NULL DEFAULT 0 COMMENT '识别出的顶层章节数量，0 表示未分章',
  `structure_status` VARCHAR(30) NOT NULL DEFAULT 'PENDING' COMMENT '知识结构图状态：PENDING待处理、READY已生成可预览、CHAPTER_ON_DEMAND大部头按章按需生成、UNSUPPORTED该格式无法抽取正文、FAILED生成失败',
  `structure_message` VARCHAR(500) NULL COMMENT '面向用户的结构图状态说明，例如“共 42 章，未自动生成，请在阅读某一章时生成”',
  `upload_session_id` BIGINT NULL COMMENT '来源分片上传会话 knowledge_upload_session.id；直传的小文件为空',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_knowledge_file_upload_session` (`upload_session_id`),
  KEY `idx_knowledge_file_user_folder` (`user_id`, `folder_id`, `id`),
  KEY `idx_knowledge_file_user_created` (`user_id`, `created_at`, `id`),
  KEY `idx_knowledge_file_user_name` (`user_id`, `display_name`(120)),
  KEY `idx_knowledge_file_user_hash` (`user_id`, `file_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库文件（必须归属某个非根文件夹）';

CREATE TABLE `knowledge_file_content` (
  `file_id` BIGINT NOT NULL COMMENT '文件主键 knowledge_file.id；与文件一对一，文件删除时同步删除',
  `user_id` BIGINT NOT NULL COMMENT '所属用户 sys_user.id；按用户回收内容时用得到',
  `content` LONGTEXT NOT NULL COMMENT '抽取出的纯文本正文。knowledge_chapter 的 start_offset/end_offset 以它为坐标系，按章按需生成结构图时也要回读它；正文只在这里存一份，不与 knowledge_file 同表，避免列表查询把大文本全捞出来',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '抽取时间',
  PRIMARY KEY (`file_id`),
  KEY `idx_knowledge_file_content_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库文件抽取出的纯文本正文（章节偏移与按需结构图的数据源）';

CREATE TABLE `knowledge_file_tag` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '文件标签关联主键',
  `user_id` BIGINT NOT NULL COMMENT '冗余的所属用户 sys_user.id；按标签反查文件时不必再回表 knowledge_file',
  `file_id` BIGINT NOT NULL COMMENT '关联文件 knowledge_file.id',
  `tag_id` BIGINT NOT NULL COMMENT '关联标签 knowledge_tag.id',
  `source` VARCHAR(10) NOT NULL DEFAULT 'MANUAL' COMMENT '标签来源：MANUAL用户手动添加、AUTO由章节标题自动生成；自动标签可被整体重算，手动标签不会被覆盖',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '打标时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_knowledge_file_tag` (`file_id`, `tag_id`),
  KEY `idx_knowledge_file_tag_user_tag` (`user_id`, `tag_id`, `file_id`),
  KEY `idx_knowledge_file_tag_file` (`file_id`, `source`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库文件与标签的多对多关联';

CREATE TABLE `knowledge_upload_session` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '分片上传会话主键',
  `user_id` BIGINT NOT NULL COMMENT '会话所属用户 sys_user.id',
  `upload_id` CHAR(36) NOT NULL COMMENT '对外暴露的会话标识（UUID）；断点续传由客户端凭它查询已收分片',
  `folder_id` BIGINT NOT NULL COMMENT '目标文件夹 knowledge_folder.id，必须大于 0',
  `original_name` VARCHAR(255) NOT NULL COMMENT '原始文件名（已过滤路径分隔符）',
  `content_type` VARCHAR(120) NULL COMMENT '客户端声明的 MIME 类型',
  `size_bytes` BIGINT NOT NULL COMMENT '声明的文件总字节数；合并后实际大小不一致即拒绝完成',
  `chunk_size` INT NOT NULL COMMENT '分片大小（字节），最后一片可小于该值',
  `total_chunks` INT NOT NULL COMMENT '分片总数，由服务端按 size_bytes 与 chunk_size 计算，不信任客户端传入',
  `file_hash` CHAR(64) NOT NULL COMMENT '客户端声明的整文件 SHA-256；既是续传的识别依据，也用于合并后校验完整性',
  `received_chunks` INT NOT NULL DEFAULT 0 COMMENT '已确认落盘的分片数，仅作展示；权威来源是 knowledge_upload_chunk',
  `status` VARCHAR(20) NOT NULL COMMENT '会话状态：UPLOADING上传中、COMPLETED已合并入库、ABORTED已放弃、EXPIRED已过期回收',
  `idempotency_key` CHAR(64) NULL COMMENT '活跃会话去重键 SHA-256(userId|folderId|fileHash|sizeBytes)；会话结束后置 NULL，让同一文件日后可以重新上传（MySQL 唯一索引不约束 NULL）',
  `file_id` BIGINT NULL COMMENT '完成后生成的 knowledge_file.id；重复调用完成接口时直接返回它，保证幂等',
  `expires_at` DATETIME NOT NULL COMMENT '会话过期时间，超时后分片可被回收，客户端需重新初始化',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_knowledge_upload_session_upload_id` (`upload_id`),
  UNIQUE KEY `uk_knowledge_upload_session_idempotency` (`user_id`, `idempotency_key`),
  KEY `idx_knowledge_upload_session_user_status` (`user_id`, `status`, `id`),
  KEY `idx_knowledge_upload_session_expires` (`status`, `expires_at`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库分片上传会话（支持断点续传与完成幂等）';

CREATE TABLE `knowledge_upload_chunk` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '分片记录主键',
  `session_id` BIGINT NOT NULL COMMENT '所属会话 knowledge_upload_session.id',
  `chunk_index` INT NOT NULL COMMENT '分片序号，从 0 开始',
  `size_bytes` INT NOT NULL COMMENT '该分片实际字节数',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '落盘确认时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_knowledge_upload_chunk` (`session_id`, `chunk_index`),
  KEY `idx_knowledge_upload_chunk_session` (`session_id`, `chunk_index`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库已确认落盘的上传分片（重复上传同一分片幂等）';

CREATE TABLE `knowledge_chapter` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '章节主键',
  `user_id` BIGINT NOT NULL COMMENT '章节所属用户 sys_user.id',
  `file_id` BIGINT NOT NULL COMMENT '所属文件 knowledge_file.id',
  `parent_id` BIGINT NOT NULL DEFAULT 0 COMMENT '父章节 knowledge_chapter.id；0 表示顶层章。小节挂在章下面，形成阅读目录树',
  `level` INT NOT NULL COMMENT '标题层级，1 为顶层章；由 Markdown 井号数或“第N章/第N节”句式推导',
  `order_index` INT NOT NULL COMMENT '同一文件内的全局排序序号，从 0 开始，按正文出现顺序递增',
  `title` VARCHAR(300) NOT NULL COMMENT '章节标题原文（已去掉井号与多余空白）',
  `start_offset` INT NOT NULL COMMENT '章节正文在抽取文本中的起始字符下标（含）',
  `end_offset` INT NOT NULL COMMENT '章节正文在抽取文本中的结束字符下标（不含）',
  `char_count` INT NOT NULL DEFAULT 0 COMMENT '章节正文字符数，用于判断是否值得单独出结构图',
  `structure_status` VARCHAR(30) NOT NULL DEFAULT 'PENDING' COMMENT '本章知识结构图状态：PENDING未生成、READY已生成、FAILED生成失败；大部头文件的章节初始都是 PENDING，用户看到哪一章再生成哪一章',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_knowledge_chapter_file_order` (`file_id`, `order_index`),
  KEY `idx_knowledge_chapter_user_file` (`user_id`, `file_id`, `order_index`),
  KEY `idx_knowledge_chapter_parent` (`file_id`, `parent_id`, `order_index`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库文件的章节目录（自动识别结果）';

CREATE TABLE `knowledge_structure_node` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '知识结构图节点主键',
  `user_id` BIGINT NOT NULL COMMENT '节点所属用户 sys_user.id',
  `file_id` BIGINT NOT NULL COMMENT '所属文件 knowledge_file.id',
  `scope_chapter_id` BIGINT NOT NULL DEFAULT 0 COMMENT '该节点属于哪一次生成：0 表示整本书维度的结构图，非 0 表示只针对 knowledge_chapter.id 这一章生成。重新生成时按 (file_id, scope_chapter_id) 整批替换',
  `parent_id` BIGINT NOT NULL DEFAULT 0 COMMENT '父节点 knowledge_structure_node.id；0 表示本次生成的根节点。图的边由父子关系推导，不额外建边表',
  `node_type` VARCHAR(20) NOT NULL COMMENT '节点类型：ROOT根节点（文件或章）、CHAPTER章、SECTION节、POINT知识点',
  `title` VARCHAR(300) NOT NULL COMMENT '节点标题',
  `summary` VARCHAR(500) NULL COMMENT '节点摘要，取该节点正文开头的一段，供预览时展示',
  `order_index` INT NOT NULL COMMENT '同一父节点下的排序序号，从 0 开始',
  `chapter_id` BIGINT NULL COMMENT '该节点对应的 knowledge_chapter.id；纯知识点节点为空，前端可据此跳转到原文位置',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_knowledge_structure_scope` (`file_id`, `scope_chapter_id`, `parent_id`, `order_index`),
  KEY `idx_knowledge_structure_user_file` (`user_id`, `file_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识结构图节点（树形，边由 parent_id 推导）';

-- ── 2. 共享通知表扩展：通用资源引用 ─────────────────────────────────
-- 知识库要发“这本书有 42 章，未自动生成结构图”这类通知，用户点开必须能回到那个文件。
-- plan_id / run_id 是定时发文计划专用列（各自指向 blog_content_plan / blog_plan_run），
-- 塞文件 ID 进去会让通知列表出现指不到任何计划的脏引用，因此新增一组与业务无关的引用列。
-- type 同时放宽到 48 位：原 VARCHAR(24) 装不下 KNOWLEDGE_STRUCTURE_PENDING 这类语义完整的类型名。
ALTER TABLE `blog_notification`
  MODIFY COLUMN `type` VARCHAR(48) NOT NULL COMMENT '通知类型：REVIEW_REJECTED/CSDN_FAILED/JUEJIN_FAILED/RUN_FAILED/PLAN_STALLED/KNOWLEDGE_STRUCTURE_PENDING',
  ADD COLUMN `resource_type` VARCHAR(32) NULL COMMENT '通用关联资源类型，如 KNOWLEDGE_FILE；与 plan_id/run_id 互斥使用，供非计划类通知回跳' AFTER `run_id`,
  ADD COLUMN `resource_id` BIGINT NULL COMMENT '通用关联资源主键，配合 resource_type 解释；为空表示该通知没有可回跳的资源' AFTER `resource_type`;
