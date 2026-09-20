-- V9 Wave 2 content contract compatibility.
--
-- The historical blog baseline predates the per-user publishing API.  The
-- current ContentPostPort deliberately scopes every read and write by
-- blog_post.user_id, so a fresh database must receive the same projection
-- before content-automation can call core-host.  Existing installations may
-- already contain either column; use information_schema guards so the single
-- migration runner is safe for both shapes.

SET @blog_post_user_id_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'blog_post'
      AND COLUMN_NAME = 'user_id'
);
SET @blog_post_user_id_sql := IF(
    @blog_post_user_id_exists = 0,
    'ALTER TABLE `blog_post` ADD COLUMN `user_id` BIGINT NULL COMMENT ''文章所属用户ID，关联 sys_user.id'' AFTER `id`, ADD KEY `idx_blog_post_user_created` (`user_id`, `created_at`, `id`)',
    'SELECT 1'
);
PREPARE blog_post_user_id_statement FROM @blog_post_user_id_sql;
EXECUTE blog_post_user_id_statement;
DEALLOCATE PREPARE blog_post_user_id_statement;

SET @blog_post_cover_image_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'blog_post'
      AND COLUMN_NAME = 'cover_image'
);
SET @blog_post_cover_image_sql := IF(
    @blog_post_cover_image_exists = 0,
    'ALTER TABLE `blog_post` ADD COLUMN `cover_image` VARCHAR(1024) NULL COMMENT ''文章封面图片URL'' AFTER `content`',
    'SELECT 1'
);
PREPARE blog_post_cover_image_statement FROM @blog_post_cover_image_sql;
EXECUTE blog_post_cover_image_statement;
DEALLOCATE PREPARE blog_post_cover_image_statement;
