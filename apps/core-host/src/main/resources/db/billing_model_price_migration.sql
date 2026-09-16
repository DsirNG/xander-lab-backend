-- 积分计费：新增模型定价表（管理员可在后台自定义各模型 token 单价）。
-- 计费顺序：本表按 model 精确匹配（enabled=1）→ 未命中回退 application.yml billing.price 默认价。
-- 存量库手工执行本文件；新库直接执行 billing_schema.sql 即可。

CREATE TABLE IF NOT EXISTS billing_model_price (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    model VARCHAR(128) NOT NULL COMMENT '模型名称，与模型网关返回的 model 字段一致（如 gpt-5.6-terra）',
    input_milli_per_1k_token BIGINT NOT NULL COMMENT '输入 token 单价：毫分 / 每 1K token（按 1 元 = 10 积分，¥2/M = 20）',
    output_milli_per_1k_token BIGINT NOT NULL COMMENT '输出 token 单价：毫分 / 每 1K token（按 1 元 = 10 积分，¥12/M = 120）',
    cached_milli_per_1k_token BIGINT NOT NULL DEFAULT 0 COMMENT '缓存读取 token 单价：毫分 / 每 1K token（按 1 元 = 10 积分，¥0.2/M = 2）',
    cached_write_milli_per_1k_token BIGINT NOT NULL DEFAULT 0 COMMENT '缓存写入 token 单价：毫分 / 每 1K token（按 1 元 = 10 积分，¥2.5/M = 25）；当前 usage 无缓存写入计量，预留',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用：1=参与计费，0=停用后回退 application.yml 默认价',
    remark VARCHAR(255) NULL COMMENT '备注：价目来源、生效说明等',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近修改时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_model_price_model (model)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '模型 token 定价（管理员维护）：计费时按 model 精确匹配，未配置回退 yml 默认价';

-- 预置当前在用模型的价目（CodexPro订阅 1x 档，0_272k 阶梯）。
INSERT IGNORE INTO billing_model_price
    (model, input_milli_per_1k_token, output_milli_per_1k_token, cached_milli_per_1k_token, cached_write_milli_per_1k_token, enabled, remark)
VALUES
    ('gpt-5.6-terra', 20, 120, 2, 25, 1, '1元=10积分；输入¥2/M 输出¥12/M 缓存读¥0.2/M 缓存写¥2.5/M'),
    ('gpt-image-2', 50, 300, 0, 0, 1, '1元=10积分；输入¥5/M 输出¥30/M');
