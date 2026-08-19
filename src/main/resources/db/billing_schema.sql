-- 积分计费模块基线表。
-- user_id 即 sys_user 主键；流水 biz_type+biz_key 联合唯一，保证幂等不重复扣费。
-- 单位：积分，最小 1 分；token 单价快照以「毫分 / 每 1K token」保存，仅作审计展示。

CREATE TABLE IF NOT EXISTS sys_user_points (
    user_id BIGINT NOT NULL COMMENT '用户主键（sys_user.id）',
    balance BIGINT NOT NULL DEFAULT 0 COMMENT '当前积分余额，允许为负（先耗后扣导致的小额欠费）',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '开户时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近变动时间',
    PRIMARY KEY (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户积分账户：余额可为 0 或负，回复按新请求每次判断是否过低';

CREATE TABLE IF NOT EXISTS points_ledger (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '流水主键',
    user_id BIGINT NOT NULL COMMENT '用户主键（sys_user.id）',
    biz_type VARCHAR(32) NOT NULL COMMENT '计费方向：grant/consume/refund，或业务枚举值',
    biz_key VARCHAR(128) NOT NULL COMMENT '幂等键，与 biz_type 联合唯一：如 task:123:run、conv:5:run:2',
    biz_ref VARCHAR(128) NULL COMMENT '业务引用：taskId / conversationId+runVersion / planRunId 等',
    amount BIGINT NOT NULL COMMENT '积分变动额（正数），方向由 biz_type 决定',
    balance_after BIGINT NOT NULL COMMENT '变动后余额快照',
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

-- 注册即送：给存量用户补齐账户行，方便计费开关打开后直接使用。
INSERT IGNORE INTO sys_user_points (user_id, balance, created_at, updated_at)
SELECT id, 2000, NOW(), NOW() FROM sys_user WHERE id NOT IN (SELECT user_id FROM sys_user_points);
