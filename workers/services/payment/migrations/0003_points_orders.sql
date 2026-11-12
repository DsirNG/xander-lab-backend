-- 每份永久积分商品为 10 积分 / 100 分人民币；微信后台须发布同名道具。
ALTER TABLE virtual_pay_settings ADD COLUMN points_product_id TEXT NOT NULL DEFAULT ''; -- 永久积分道具 ID
ALTER TABLE virtual_pay_order ADD COLUMN purchase_kind TEXT NOT NULL DEFAULT 'subscription' CHECK(purchase_kind IN ('subscription','points')); -- 购买类型
ALTER TABLE virtual_pay_order ADD COLUMN points INTEGER NOT NULL DEFAULT 0 CHECK(points>=0); -- 发放积分，非毫积分
