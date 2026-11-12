-- 退款独立状态不覆盖原支付状态；原订单金额单位为分。
CREATE TABLE virtual_pay_refund (
 refund_id TEXT PRIMARY KEY, -- 稳定商户退款号
 out_trade_no TEXT NOT NULL UNIQUE REFERENCES virtual_pay_order(out_trade_no), -- 每笔支付最多一次剩余权益退款
 external INTEGER NOT NULL DEFAULT 0 CHECK(external IN (0,1)), -- 平台发起退款，禁止向平台再次申请
 status TEXT NOT NULL CHECK(status IN ('PREPARING','SUBMITTING','PROCESSING','REVIEW','SUCCEEDED','FAILED')), -- 持久化退款状态
 refund_fee INTEGER NOT NULL DEFAULT 0 CHECK(refund_fee>=0), -- 实际退款分
 left_fee INTEGER NOT NULL DEFAULT 0 CHECK(left_fee>=0), -- 发起时平台可退分
 points_amount INTEGER NOT NULL DEFAULT 0 CHECK(points_amount>=0), -- 冻结毫积分
 wx_refund_id TEXT, -- 微信退款单号
 created_at TEXT NOT NULL DEFAULT(strftime('%Y-%m-%dT%H:%M:%fZ','now')), -- UTC 创建时间
 updated_at TEXT NOT NULL DEFAULT(strftime('%Y-%m-%dT%H:%M:%fZ','now')) -- UTC 状态变更时间
);
CREATE INDEX idx_virtual_refund_pending ON virtual_pay_refund(status,updated_at);
