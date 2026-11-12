-- 永久积分按购买订单记录；历史余额不推算可退款归属。
CREATE TABLE purchased_points_lot (
 event_key TEXT PRIMARY KEY, -- 支付事件键
 user_id INTEGER NOT NULL REFERENCES sys_user(id), -- 当前权益所有者
 original INTEGER NOT NULL CHECK(original>0), -- 购买毫积分
 remaining INTEGER NOT NULL CHECK(remaining>=0), -- 未消费且未冻结毫积分
 created_at TEXT NOT NULL DEFAULT(strftime('%Y-%m-%dT%H:%M:%fZ','now')) -- UTC 发放时间
);
CREATE INDEX idx_purchased_points_owner ON purchased_points_lot(user_id,created_at,event_key);
CREATE TABLE payment_refund_hold (
 refund_id TEXT PRIMARY KEY, -- Payment 稳定退款编号
 event_key TEXT NOT NULL UNIQUE, -- 每个订单最多一笔退款，失败需人工处理
 user_id INTEGER NOT NULL REFERENCES sys_user(id), -- 退款时所有者
 amount INTEGER NOT NULL CHECK(amount>=0), -- 冻结毫积分
 state TEXT NOT NULL DEFAULT 'HELD' CHECK(state IN ('HELD','COMPLETED','RELEASED')), -- 冻结、回收、恢复
 created_at TEXT NOT NULL DEFAULT(strftime('%Y-%m-%dT%H:%M:%fZ','now')) -- UTC 创建时间
);
-- 余额变化与批次分配处于同一插入事务。先用套餐，再按购买顺序使用永久积分。
DROP TRIGGER points_ledger_apply_balance;
CREATE TRIGGER points_ledger_apply_balance AFTER INSERT ON points_ledger BEGIN
 UPDATE purchased_points_lot SET remaining=purchased_points_lot.remaining-MIN(purchased_points_lot.remaining,
   MAX(0,-COALESCE(NEW.balance_delta,CASE WHEN NEW.biz_type IN ('grant','merge-credit') THEN NEW.amount ELSE -NEW.amount END)
    -COALESCE((SELECT SUM(remaining) FROM subscription_points_lot WHERE user_id=NEW.user_id AND julianday(expires_at)>julianday('now')),0)
    -COALESCE(allocation.preceding,0)))
 FROM (SELECT event_key,SUM(remaining) OVER(ORDER BY created_at,event_key ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING) preceding
   FROM purchased_points_lot WHERE user_id=NEW.user_id) allocation
 WHERE purchased_points_lot.event_key=allocation.event_key AND NEW.biz_type NOT IN ('subscription-expire','refund-hold')
   AND COALESCE(NEW.balance_delta,CASE WHEN NEW.biz_type IN ('grant','merge-credit') THEN NEW.amount ELSE -NEW.amount END)<0;
 UPDATE subscription_points_lot SET remaining=subscription_points_lot.remaining-MIN(subscription_points_lot.remaining,
   MAX(0,-COALESCE(NEW.balance_delta,CASE WHEN NEW.biz_type IN ('grant','merge-credit') THEN NEW.amount ELSE -NEW.amount END)-COALESCE(allocation.preceding,0)))
 FROM (SELECT event_key,SUM(remaining) OVER(ORDER BY expires_at,event_key ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING) preceding
   FROM subscription_points_lot WHERE user_id=NEW.user_id AND julianday(expires_at)>julianday('now')) allocation
 WHERE subscription_points_lot.event_key=allocation.event_key AND NEW.biz_type NOT IN ('subscription-expire','refund-hold')
   AND COALESCE(NEW.balance_delta,CASE WHEN NEW.biz_type IN ('grant','merge-credit') THEN NEW.amount ELSE -NEW.amount END)<0;
 UPDATE sys_user_points SET balance=NEW.balance_after,updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE user_id=NEW.user_id;
 INSERT INTO purchased_points_lot(event_key,user_id,original,remaining)
 SELECT NEW.biz_key,NEW.user_id,NEW.amount,NEW.amount WHERE NEW.biz_type='points-purchase';
END;
-- 拒绝账户合并穿过进行中的退款，防止退款恢复到已合并账户。
CREATE TRIGGER refund_hold_blocks_merge BEFORE DELETE ON sys_user_points
WHEN EXISTS(SELECT 1 FROM payment_refund_hold WHERE user_id=OLD.user_id AND state='HELD')
BEGIN SELECT RAISE(ABORT,'refund_in_progress'); END;
CREATE TRIGGER payment_refund_hold_apply AFTER INSERT ON payment_refund_hold BEGIN
 UPDATE purchased_points_lot SET remaining=remaining-NEW.amount WHERE event_key=NEW.event_key AND user_id=NEW.user_id;
 UPDATE subscription_points_lot SET remaining=remaining-NEW.amount WHERE event_key=NEW.event_key AND user_id=NEW.user_id;
 INSERT INTO points_ledger(user_id,biz_type,biz_key,amount,balance_after,balance_delta,remark)
 SELECT NEW.user_id,'refund-hold',NEW.refund_id,NEW.amount,balance-NEW.amount,-NEW.amount,'退款冻结积分'
 FROM sys_user_points WHERE user_id=NEW.user_id;
END;
CREATE TRIGGER payment_refund_release AFTER UPDATE OF state ON payment_refund_hold
WHEN OLD.state='HELD' AND NEW.state='RELEASED' BEGIN
 UPDATE purchased_points_lot SET remaining=remaining+NEW.amount WHERE event_key=NEW.event_key AND user_id=NEW.user_id;
 UPDATE subscription_points_lot SET remaining=remaining+NEW.amount WHERE event_key=NEW.event_key AND user_id=NEW.user_id;
 INSERT INTO points_ledger(user_id,biz_type,biz_key,amount,balance_after,balance_delta,remark)
 SELECT NEW.user_id,'refund-release',NEW.refund_id,NEW.amount,balance+NEW.amount,NEW.amount,'退款失败恢复积分'
 FROM sys_user_points WHERE user_id=NEW.user_id;
END;

-- 退款终止该订单会员；后续升级订单不受影响。
CREATE TRIGGER payment_refund_complete AFTER UPDATE OF state ON payment_refund_hold
WHEN OLD.state='HELD' AND NEW.state='COMPLETED' BEGIN
 UPDATE sys_user SET tier='FREE',tier_expires_at=NULL WHERE id=NEW.user_id
 AND EXISTS(SELECT 1 FROM payment_entitlement_grant g WHERE g.event_key=NEW.event_key AND g.user_id=NEW.user_id
   AND g.tier=sys_user.tier
   AND NOT EXISTS(SELECT 1 FROM payment_entitlement_grant newer WHERE newer.user_id=g.user_id AND newer.rowid>g.rowid));
END;
CREATE VIEW refundable_points_lot AS
 SELECT event_key,user_id,original,remaining FROM purchased_points_lot
 UNION ALL
 SELECT l.event_key,l.user_id,p.amount AS original,
   CASE WHEN julianday(l.expires_at)>julianday('now') THEN l.remaining ELSE 0 END AS remaining
 FROM subscription_points_lot l JOIN points_ledger p ON p.biz_key=l.event_key AND p.biz_type='subscription-grant';
