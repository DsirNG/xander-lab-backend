ALTER TABLE `blog_post`
  ADD COLUMN `cover_image` VARCHAR(1024) NULL COMMENT '文章封面图片URL，前端可选传入；为空时同步时可回退到正文首图' AFTER `content`;
