-- 模型计费方式：TOKEN 按用量，REQUEST 每次模型调用固定收费；存量默认 TOKEN。
ALTER TABLE billing_model_price ADD COLUMN billing_mode TEXT NOT NULL DEFAULT 'TOKEN' CHECK (billing_mode IN ('TOKEN', 'REQUEST'));
-- 每次调用价格，单位毫积分（1 元 = 10000 毫积分）；0 表示免费。
ALTER TABLE billing_model_price ADD COLUMN milli_per_request INTEGER NOT NULL DEFAULT 0 CHECK (milli_per_request >= 0);
