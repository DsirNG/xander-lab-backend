-- 按用户、商品、数量查找十五分钟内待支付订单，支持原子复用。
CREATE INDEX IF NOT EXISTS idx_virtual_pay_checkout ON virtual_pay_order(user_id, product_id, quantity, status, created_at);
