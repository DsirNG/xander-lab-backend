import type { D1Binding } from "@dinqor/worker-runtime";
import type { PaymentEntitlementRepository } from "../interfaces/payment-entitlement-repository.js";

export class D1PaymentEntitlementMapper implements PaymentEntitlementRepository {
  constructor(private readonly db: D1Binding) {}

  async grantOnce(userId: number, tier: string, durationDays: number, eventKey: string): Promise<boolean> {
    const inserted = await this.db.prepare(`
      INSERT INTO payment_entitlement_grant (event_key, user_id, tier, duration_days)
      VALUES (?, ?, ?, ?)
      ON CONFLICT(event_key) DO NOTHING
      RETURNING event_key AS eventKey
    `).bind(eventKey, userId, tier, durationDays).first<{ eventKey: string }>();
    return inserted !== null;
  }
}
