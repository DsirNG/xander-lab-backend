-- 供应商可选模型目录，JSON 字符串数组；仅用于配置选择，不限制手动填写模型。
ALTER TABLE model_provider ADD COLUMN models_json TEXT NOT NULL DEFAULT '[]' CHECK(json_valid(models_json) AND json_type(models_json) = 'array');
