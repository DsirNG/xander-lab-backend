import type { D1Binding } from "@dinqor/worker-runtime";
import type { PaymentEntitlementRepository } from "../interfaces/payment-entitlement-repository.js";

import { pointsExpiryStatements } from "./points-expiry.js";

import { D1PaymentRefundMapper } from "./d1-payment-refund-mapper.js";

export class D1PaymentEntitlementMapper implements PaymentEntitlementRepository {
  constructor(private readonly db: D1Binding) {}

  refundPoints(action: "quote" | "reserve" | "complete" | "release", userId: number, eventKey: string, refundId: string) {
    const refunds = new D1PaymentRefundMapper(this.db);
    if (action === "quote") return refunds.quote(userId,eventKey);
    if (action === "reserve") return refunds.reserve(userId,eventKey,refundId);
    return refunds.settle(userId,eventKey,refundId,action === "complete");
  }

  subscription(userId: number) {
    return this.db
      .prepare(`SELECT tier,tier_expires_at AS tierExpiresAt FROM sys_user WHERE id=? AND status=1`)
      .bind(userId)
      .first<{ tier: string; tierExpiresAt: string | null }>();
  }

  async grantPoints(userId: number, amount: number, eventKey: string) {
    const results = await this.db.batch([
      ...pointsExpiryStatements(this.db, userId),
      this.db
        .prepare(`INSERT INTO sys_user_points(user_id,balance) VALUES(?,0) ON CONFLICT(user_id) DO NOTHING`)
        .bind(userId),
      this.db
        .prepare(
          `INSERT INTO points_ledger(user_id,biz_type,biz_key,amount,balance_after,balance_delta,remark)
        SELECT ?, 'points-purchase', ?, ?, balance+?, ?, '购买永久积分' FROM sys_user_points WHERE user_id=?
        ON CONFLICT(biz_type,biz_key) DO NOTHING RETURNING id`,
        )
        .bind(userId, eventKey, amount, amount, amount, userId),
    ]);
    return (results.at(-1)?.meta.changes ?? 0) > 0;
  }

  async grantOnce(userId: number, tier: string, durationDays: number, eventKey: string): Promise<boolean> {
    const results = await this.db.batch([
      ...pointsExpiryStatements(this.db, userId),
      this.db
        .prepare(
          `
      INSERT INTO payment_entitlement_grant (event_key, user_id, tier, duration_days)
      VALUES (?, ?, ?, ?)
      ON CONFLICT(event_key) DO NOTHING
      RETURNING event_key AS eventKey
    `,
        )
        .bind(eventKey, userId, tier, durationDays),
    ]);
    return (results.at(-1)?.meta.changes ?? 0) > 0;
  }
}
