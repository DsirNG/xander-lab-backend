-- 支付宝网页收款订单；用户 ID 仅为 Core 引用，不建立跨服务外键。
CREATE TABLE alipay_web_order (
  out_trade_no TEXT PRIMARY KEY, -- 商户订单号，服务端生成
  user_id INTEGER NOT NULL CHECK (user_id > 0), -- 所属用户
  idempotency_key TEXT NOT NULL, -- 同用户创建订单的幂等键
  product_id TEXT NOT NULL, -- 后端商品目录 ID
  tier TEXT NOT NULL CHECK (tier IN ('PLUS','PRO','ULTRA')), -- 订阅等级
  price_cents INTEGER NOT NULL CHECK (price_cents > 0), -- 应付人民币分
  duration_days INTEGER NOT NULL CHECK (duration_days BETWEEN 1 AND 3660), -- 权益天数
  app_id TEXT NOT NULL, -- 下单时的应用 ID
  seller_id TEXT NOT NULL, -- 下单时的收款主体
  environment TEXT NOT NULL CHECK (environment IN ('sandbox','production')), -- 环境隔离
  status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','PAID','DELIVERED','CLOSED','REFUND_PENDING','REFUNDED')),
  trade_no TEXT UNIQUE, -- 已验签的支付宝交易号
  delivered_at TEXT, -- 权益确认发放时间
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  UNIQUE(user_id, idempotency_key)
);
CREATE INDEX idx_alipay_web_order_user ON alipay_web_order(user_id, created_at DESC);
-- 全额退款使用每订单唯一退款号；未知结果保留同号重试，不产生第二笔退款。
CREATE TABLE alipay_web_refund (
  out_request_no TEXT PRIMARY KEY, -- 退款幂等号
  out_trade_no TEXT NOT NULL UNIQUE REFERENCES alipay_web_order(out_trade_no), -- 本库订单
  amount_cents INTEGER NOT NULL CHECK(amount_cents > 0), -- 全额退款分
  status TEXT NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','SUCCEEDED')),
  entitlement_review_required INTEGER NOT NULL DEFAULT 1 CHECK(entitlement_review_required IN (0,1)), -- 管理员需处理已发订阅权益
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
-- 通知仅保存必要审计字段，不保存签名、买家个人信息或原始通知。
CREATE TABLE alipay_web_notification (
  app_id TEXT NOT NULL, -- 验证后的应用
  environment TEXT NOT NULL, -- 沙箱或生产
  notify_id TEXT NOT NULL, -- 支付宝通知 ID
  out_trade_no TEXT NOT NULL REFERENCES alipay_web_order(out_trade_no), -- 本库订单
  trade_no TEXT NOT NULL, -- 支付宝交易号
  trade_status TEXT NOT NULL, -- 通知交易状态
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  PRIMARY KEY(app_id, environment, notify_id)
);
