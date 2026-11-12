import type { D1Binding } from "@dinqor/worker-runtime";

/** Settle expired subscription lots in the same batch as the next mutation. */
export function pointsExpiryStatements(db: D1Binding, userId: number) {
  return [
    db
      .prepare(
        `
    INSERT INTO points_ledger(user_id,biz_type,biz_key,amount,balance_after,balance_delta,remark)
    SELECT p.user_id,'subscription-expire',l.event_key,l.remaining,
      p.balance - SUM(l.remaining) OVER (ORDER BY l.expires_at,l.event_key),-l.remaining,'套餐积分到期'
    FROM subscription_points_lot l JOIN sys_user_points p ON p.user_id=l.user_id
    WHERE l.user_id=? AND l.remaining>0 AND julianday(l.expires_at)<=julianday('now')
    ORDER BY l.expires_at,l.event_key
    ON CONFLICT(biz_type,biz_key) DO NOTHING
  `,
      )
      .bind(userId),
    db
      .prepare(
        `UPDATE subscription_points_lot SET remaining=0
    WHERE user_id=? AND julianday(expires_at)<=julianday('now')`,
      )
      .bind(userId),
  ];
}
