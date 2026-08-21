-- 积分计费模块基线表。
-- user_id 即 sys_user 主键；流水 biz_type+biz_key 联合唯一，保证幂等不重复扣费。
-- 单位：毫分（1 分 = 1000 毫分），余额与流水全链路毫分；token 计费精确到毫分。
-- token 单价快照以「毫分 / 每 1K token」保存，仅作审计展示。

CREATE TABLE IF NOT EXISTS sys_user_points (
    user_id BIGINT NOT NULL COMMENT '用户主键（sys_user.id）',
    balance BIGINT NOT NULL DEFAULT 0 COMMENT '当前余额（毫分），允许为负（先耗后扣导致的小额欠费）',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '开户时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近变动时间',
    PRIMARY KEY (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户积分账户：余额可为 0 或负，回复按新请求每次判断是否过低';

CREATE TABLE IF NOT EXISTS points_ledger (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '流水主键',
    user_id BIGINT NOT NULL COMMENT '用户主键（sys_user.id）',
    biz_type VARCHAR(32) NOT NULL COMMENT '计费方向：grant/merge-credit 为收入，其余为消耗',
    biz_key VARCHAR(128) NOT NULL COMMENT '幂等键，与 biz_type 联合唯一：如 task:123:run、conv:5:run:2',
    biz_ref VARCHAR(128) NULL COMMENT '业务引用：taskId / conversationId+runVersion / planRunId 等',
    amount BIGINT NOT NULL COMMENT '毫分变动额（恒为正数），方向由 biz_type 决定',
    balance_after BIGINT NOT NULL COMMENT '变动后余额快照（毫分）',
    unit_price VARCHAR(255) NULL COMMENT '扣费时单价快照（token 单价或一口价说明）',
    model VARCHAR(128) NULL COMMENT 'token 计费用的模型名（流水审计）',
    input_tokens BIGINT NULL COMMENT '输入 token 数（token 计费时非空）',
    output_tokens BIGINT NULL COMMENT '输出 token 数（token 计费时非空）',
    cached_tokens BIGINT NULL COMMENT '缓存命中 token 数（token 计费时非空）',
    remark VARCHAR(500) NULL COMMENT '业务说明',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '流水时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_ledger_biz (biz_type, biz_key),
    KEY idx_ledger_user_time (user_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '积分流水（审计）：(biz_type,biz_key) 唯一保证计费幂等';

CREATE TABLE IF NOT EXISTS billing_model_price (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    model VARCHAR(128) NOT NULL COMMENT '模型名称，与模型网关返回的 model 字段一致（如 gpt-5.6-terra）',
    input_milli_per_1k_token BIGINT NOT NULL COMMENT '输入 token 单价：毫分 / 每 1K token（¥2/M = 200）',
    output_milli_per_1k_token BIGINT NOT NULL COMMENT '输出 token 单价：毫分 / 每 1K token（¥12/M = 1200）',
    cached_milli_per_1k_token BIGINT NOT NULL DEFAULT 0 COMMENT '缓存读取 token 单价：毫分 / 每 1K token（¥0.2/M = 20）',
    cached_write_milli_per_1k_token BIGINT NOT NULL DEFAULT 0 COMMENT '缓存写入 token 单价：毫分 / 每 1K token（¥2.5/M = 250）；当前 usage 无缓存写入计量，预留',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用：1=参与计费，0=停用后回退 application.yml 默认价',
    remark VARCHAR(255) NULL COMMENT '备注：价目来源、生效说明等',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近修改时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_model_price_model (model)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '模型 token 定价（管理员维护）：计费时按 model 精确匹配，未配置回退 yml 默认价';

-- 注册即送：给存量用户补齐账户行，方便计费开关打开后直接使用。
INSERT IGNORE INTO sys_user_points (user_id, balance, created_at, updated_at)
SELECT id, 2000, NOW(), NOW() FROM sys_user WHERE id NOT IN (SELECT user_id FROM sys_user_points);
