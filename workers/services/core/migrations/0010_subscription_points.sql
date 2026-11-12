-- 套餐积分批次；金额统一为毫积分，旧账户余额保持为永久积分。
CREATE TABLE subscription_points_lot (
  event_key TEXT PRIMARY KEY, -- 支付发放幂等键
  user_id INTEGER NOT NULL REFERENCES sys_user(id), -- Core 用户
  remaining INTEGER NOT NULL CHECK (remaining >= 0), -- 未消费毫积分
  expires_at TEXT NOT NULL, -- UTC 到期时间，升级不延长旧批次
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')) -- 发放时间
);
CREATE INDEX idx_subscription_points_due ON subscription_points_lot(user_id, expires_at, event_key);
ALTER TABLE points_ledger ADD COLUMN balance_delta INTEGER; -- 有符号毫积分；兼容历史记录

-- 余额和套餐批次消费必须在同一账本插入事务中完成，重复回调不触发。
DROP TRIGGER points_ledger_apply_balance;
CREATE TRIGGER points_ledger_apply_balance AFTER INSERT ON points_ledger
BEGIN
  UPDATE subscription_points_lot SET remaining = subscription_points_lot.remaining - MIN(subscription_points_lot.remaining,
    MAX(0, -COALESCE(NEW.balance_delta, CASE WHEN NEW.biz_type IN ('grant','merge-credit') THEN NEW.amount ELSE -NEW.amount END)
      - COALESCE(allocation.preceding,0)))
  FROM (SELECT event_key, SUM(remaining) OVER (ORDER BY expires_at,event_key ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING) AS preceding
    FROM subscription_points_lot WHERE user_id=NEW.user_id AND julianday(expires_at)>julianday('now')) allocation
  WHERE subscription_points_lot.event_key=allocation.event_key
    AND NEW.biz_type <> 'subscription-expire'
    AND COALESCE(NEW.balance_delta, CASE WHEN NEW.biz_type IN ('grant','merge-credit') THEN NEW.amount ELSE -NEW.amount END) < 0;
  UPDATE sys_user_points SET balance = NEW.balance_after,
    updated_at = strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE user_id = NEW.user_id;
END;

DROP TRIGGER trg_payment_entitlement_grant_apply;
-- 同一有效周期禁止重复订阅和降级；重复支付事件先由幂等键识别。
CREATE TRIGGER payment_entitlement_upgrade_only BEFORE INSERT ON payment_entitlement_grant
WHEN NOT EXISTS(SELECT 1 FROM payment_entitlement_grant WHERE event_key=NEW.event_key)
  AND EXISTS(SELECT 1 FROM sys_user WHERE id=NEW.user_id AND tier<>'FREE'
    AND (tier_expires_at IS NULL OR julianday(tier_expires_at)>julianday('now'))
    AND CASE tier WHEN 'PLUS' THEN 1 WHEN 'PRO' THEN 2 WHEN 'ULTRA' THEN 3 ELSE 0 END
      >= CASE NEW.tier WHEN 'PLUS' THEN 1 WHEN 'PRO' THEN 2 ELSE 3 END)
BEGIN SELECT RAISE(ABORT,'subscription_upgrade_conflict'); END;
CREATE TRIGGER trg_payment_entitlement_grant_apply AFTER INSERT ON payment_entitlement_grant
BEGIN
  UPDATE sys_user SET tier = NEW.tier,
    tier_expires_at = strftime('%Y-%m-%dT%H:%M:%fZ','now',printf('+%d days',NEW.duration_days)),
    updated_at = strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id = NEW.user_id;
  INSERT INTO sys_user_points(user_id,balance) VALUES(NEW.user_id,0) ON CONFLICT(user_id) DO NOTHING;
  INSERT INTO points_ledger(user_id,biz_type,biz_key,amount,balance_after,balance_delta,remark)
    SELECT NEW.user_id,'subscription-grant',NEW.event_key,
      CASE NEW.tier WHEN 'PLUS' THEN 20000 WHEN 'PRO' THEN 50000 ELSE 150000 END,
      balance + CASE NEW.tier WHEN 'PLUS' THEN 20000 WHEN 'PRO' THEN 50000 ELSE 150000 END,
      CASE NEW.tier WHEN 'PLUS' THEN 20000 WHEN 'PRO' THEN 50000 ELSE 150000 END,'套餐积分'
    FROM sys_user_points WHERE user_id=NEW.user_id;
  INSERT INTO subscription_points_lot(event_key,user_id,remaining,expires_at)
    SELECT NEW.event_key,NEW.user_id,
      CASE NEW.tier WHEN 'PLUS' THEN 20000 WHEN 'PRO' THEN 50000 ELSE 150000 END,tier_expires_at
    FROM sys_user WHERE id=NEW.user_id;
END;

-- 只读余额也实时排除过期批次；不依赖定时任务运行。
CREATE VIEW available_points AS SELECT p.user_id,
  p.balance - COALESCE(SUM(CASE WHEN julianday(l.expires_at)<=julianday('now') THEN l.remaining ELSE 0 END),0) AS balance,
  COALESCE(SUM(CASE WHEN julianday(l.expires_at)>julianday('now') THEN l.remaining ELSE 0 END),0) AS subscription_balance,
  p.balance - COALESCE(SUM(l.remaining),0) AS permanent_balance,
  MIN(CASE WHEN l.remaining>0 AND julianday(l.expires_at)>julianday('now') THEN l.expires_at END) AS next_expiry
FROM sys_user_points p LEFT JOIN subscription_points_lot l ON l.user_id=p.user_id GROUP BY p.user_id;
