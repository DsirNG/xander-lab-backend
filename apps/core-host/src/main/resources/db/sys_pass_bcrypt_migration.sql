-- Migrate sys_user.password to a BCrypt-first contract.
-- Marker: 20260808_sys_user_bcrypt
--
-- 背景：密码将统一以 BCrypt 哈希存储（AuthService 在登录成功时把历史明文自动升级为哈希）。
-- 此处仅收敛列的默认值与注释，不再默认写入明文 '123456'。
-- 线上历史数据中已是明文的行无需在此处理，登录成功后会在应用层自动升级。
-- Apply manually against the live database after the app code has been deployed.

CREATE TABLE IF NOT EXISTS `xander_schema_migration` (
  `migration_key` VARCHAR(128) NOT NULL COMMENT '迁移标识键',
  `applied_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '迁移执行时间',
  PRIMARY KEY (`migration_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据库迁移执行标记';

START TRANSACTION;

ALTER TABLE `sys_user`
  MODIFY COLUMN `password` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '密码（BCrypt 哈希，历史明文登录成功后自动升级）';

INSERT IGNORE INTO `xander_schema_migration` (`migration_key`)
VALUES ('20260808_sys_pass_bcrypt');

COMMIT;