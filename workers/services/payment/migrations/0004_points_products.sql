-- 积分卡配置；NULL 兼容旧单商品配置，空数组表示停用全部积分卡。
ALTER TABLE virtual_pay_settings ADD COLUMN points_products_json TEXT CHECK(points_products_json IS NULL OR json_valid(points_products_json));
