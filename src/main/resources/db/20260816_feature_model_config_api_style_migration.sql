-- 功能模型配置新增「接口风格」列：同一模型在不同供应商可能走不同的 API 形态
-- （如 gpt-image-2 在 okinto 只能走 /chat/completions，在 OpenAI 走 /images/generations）。
-- Apply manually. Idempotent via migration marker + information_schema guard.
--
-- 取值：
--   IMAGES_GENERATIONS = POST /images/generations（prompt + size，OpenAI 传统图片接口）
--   CHAT_COMPLETIONS   = POST /chat/completions（messages + modalities，gpt-image 系列）
--   RESPONSES          = POST /responses（Responses API，文本类功能）
-- 为空时按功能取默认：blog_agent_image=CHAT_COMPLETIONS（OpenAI 图片生成主推形态），其余=RESPONSES。

START TRANSACTION;

SET @api_style_migrated = EXISTS(
  SELECT 1
  FROM `xander_schema_migration`
  WHERE `migration_key` = '20260816_feature_model_config_api_style'
);

SET @sql_add_primary_style = IF(
  @api_style_migrated = 0
  AND NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'feature_model_config'
      AND COLUMN_NAME = 'primary_api_style'
  ),
  'ALTER TABLE `feature_model_config`
     ADD COLUMN `primary_api_style` VARCHAR(30) NULL COMMENT ''主槽位接口风格：IMAGES_GENERATIONS=/images/generations、CHAT_COMPLETIONS=/chat/completions（gpt-image 系列）、RESPONSES=/responses；为空按功能取默认''
     AFTER `primary_model`',
  'SELECT 1'
);
PREPARE stmt_primary_style FROM @sql_add_primary_style;
EXECUTE stmt_primary_style;
DEALLOCATE PREPARE stmt_primary_style;

SET @sql_add_fallback_style = IF(
  @api_style_migrated = 0
  AND NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'feature_model_config'
      AND COLUMN_NAME = 'fallback_api_style'
  ),
  'ALTER TABLE `feature_model_config`
     ADD COLUMN `fallback_api_style` VARCHAR(30) NULL COMMENT ''兜底槽位接口风格：IMAGES_GENERATIONS=/images/generations、CHAT_COMPLETIONS=/chat/completions（gpt-image 系列）、RESPONSES=/responses；为空按功能取默认''
     AFTER `fallback_model`',
  'SELECT 1'
);
PREPARE stmt_fallback_style FROM @sql_add_fallback_style;
EXECUTE stmt_fallback_style;
DEALLOCATE PREPARE stmt_fallback_style;

INSERT IGNORE INTO `xander_schema_migration` (`migration_key`)
VALUES ('20260816_feature_model_config_api_style');

COMMIT;