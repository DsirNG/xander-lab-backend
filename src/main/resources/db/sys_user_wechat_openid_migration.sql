-- ============================================================
-- Xander Lab - sys_user 增加微信小程序绑定列
-- 线上库需手工执行本文件；baseline 见 blog_schema.sql（已同步）
-- ============================================================

ALTER TABLE `sys_user`
    ADD COLUMN `openid` varchar(64) DEFAULT NULL COMMENT '微信小程序 openid（登录渠道绑定，NULL=未绑定微信）' AFTER `avatar`,
    ADD UNIQUE KEY `uk_openid` (`openid`);