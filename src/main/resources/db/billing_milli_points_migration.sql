-- 积分账本单位从「分」切换为「毫分」（1 分 = 1000 毫分）：token 计费精确到毫分，消除 ±0.5 分舍入误差。
-- 存量库手工执行本文件，且【只能执行一次】——重复执行会把余额再放大 1000 倍。
-- 执行前请备份 sys_user_points 与 points_ledger；新库直接执行 billing_schema.sql 即可。
-- 同时：部署配置中 BILLING_GRANT_REGISTER / BILLING_PRE_CHECK_MIN_BALANCE /
-- BILLING_FLAT_* 环境变量若已显式设置旧值（分），需同步 ×1000。

-- 1) 存量余额与流水金额换算为毫分。
UPDATE sys_user_points SET balance = balance * 1000;
UPDATE points_ledger SET amount = amount * 1000, balance_after = balance_after * 1000;

-- 2) 列注释同步毫分语义。
ALTER TABLE sys_user_points
    MODIFY COLUMN balance BIGINT NOT NULL DEFAULT 0 COMMENT '当前余额（毫分），允许为负（先耗后扣导致的小额欠费）';

ALTER TABLE points_ledger
    MODIFY COLUMN biz_type VARCHAR(32) NOT NULL COMMENT '计费方向：grant/merge-credit 为收入，其余为消耗',
    MODIFY COLUMN amount BIGINT NOT NULL COMMENT '毫分变动额（恒为正数），方向由 biz_type 决定',
    MODIFY COLUMN balance_after BIGINT NOT NULL COMMENT '变动后余额快照（毫分）';
