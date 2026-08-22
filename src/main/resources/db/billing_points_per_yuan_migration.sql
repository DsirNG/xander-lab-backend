-- 模型 token 定价统一按「1 元 = 10 积分」换算。
-- 仅调整仍等于旧版预置值的行，避免覆盖管理员已自定义的价格。

ALTER TABLE billing_model_price
    MODIFY COLUMN input_milli_per_1k_token BIGINT NOT NULL COMMENT '输入 token 单价：毫分 / 每 1K token（按 1 元 = 10 积分，¥2/M = 20）',
    MODIFY COLUMN output_milli_per_1k_token BIGINT NOT NULL COMMENT '输出 token 单价：毫分 / 每 1K token（按 1 元 = 10 积分，¥12/M = 120）',
    MODIFY COLUMN cached_milli_per_1k_token BIGINT NOT NULL DEFAULT 0 COMMENT '缓存读取 token 单价：毫分 / 每 1K token（按 1 元 = 10 积分，¥0.2/M = 2）',
    MODIFY COLUMN cached_write_milli_per_1k_token BIGINT NOT NULL DEFAULT 0 COMMENT '缓存写入 token 单价：毫分 / 每 1K token（按 1 元 = 10 积分，¥2.5/M = 25）；当前 usage 无缓存写入计量，预留';

UPDATE billing_model_price
SET input_milli_per_1k_token = 20,
    output_milli_per_1k_token = 120,
    cached_milli_per_1k_token = 2,
    cached_write_milli_per_1k_token = 25,
    remark = '1元=10积分；输入¥2/M 输出¥12/M 缓存读¥0.2/M 缓存写¥2.5/M'
WHERE model = 'gpt-5.6-terra'
  AND input_milli_per_1k_token = 200
  AND output_milli_per_1k_token = 1200
  AND cached_milli_per_1k_token = 20
  AND cached_write_milli_per_1k_token = 250;

UPDATE billing_model_price
SET input_milli_per_1k_token = 50,
    output_milli_per_1k_token = 300,
    remark = '1元=10积分；输入¥5/M 输出¥30/M'
WHERE model = 'gpt-image-2'
  AND input_milli_per_1k_token = 500
  AND output_milli_per_1k_token = 3000
  AND cached_milli_per_1k_token = 0
  AND cached_write_milli_per_1k_token = 0;
