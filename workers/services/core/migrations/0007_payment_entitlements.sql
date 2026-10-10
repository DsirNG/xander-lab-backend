-- Payment stays in its own D1. This Core-owned event ledger grants an
-- entitlement exactly once per verified Payment trade number.
CREATE TABLE IF NOT EXISTS payment_entitlement_grant (
  event_key TEXT PRIMARY KEY,
  user_id INTEGER NOT NULL REFERENCES sys_user(id),
  tier TEXT NOT NULL CHECK (tier IN ('PLUS', 'PRO', 'ULTRA')),
  duration_days INTEGER NOT NULL CHECK (duration_days BETWEEN 1 AND 3660),
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE TRIGGER IF NOT EXISTS trg_payment_entitlement_grant_apply
AFTER INSERT ON payment_entitlement_grant
BEGIN
  UPDATE sys_user
     SET tier = CASE
           WHEN CASE COALESCE(tier, 'FREE')
                  WHEN 'FREE' THEN 0 WHEN 'PLUS' THEN 1 WHEN 'PRO' THEN 2 WHEN 'ULTRA' THEN 3 ELSE 0 END
                > CASE NEW.tier
                    WHEN 'FREE' THEN 0 WHEN 'PLUS' THEN 1 WHEN 'PRO' THEN 2 WHEN 'ULTRA' THEN 3 ELSE 0 END
           THEN COALESCE(tier, 'FREE')
           ELSE NEW.tier
         END,
         tier_expires_at = strftime('%Y-%m-%dT%H:%M:%fZ', datetime(
           CASE
             WHEN tier_expires_at IS NOT NULL AND julianday(tier_expires_at) > julianday('now')
               THEN tier_expires_at
             ELSE 'now'
           END,
           printf('+%d days', NEW.duration_days)
         )),
         updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
   WHERE id = NEW.user_id;
END;
