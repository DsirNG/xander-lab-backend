import type { PaymentEntitlementRepository } from "../interfaces/payment-entitlement-repository.js";

const paidTiers = new Set(["PLUS", "PRO", "ULTRA"]);

export class PaymentEntitlementService {
  constructor(private readonly entitlements: PaymentEntitlementRepository) {}

  async grantOnce(userId: number, tier: string, durationDays: number, eventKey: string): Promise<boolean> {
    if (!Number.isSafeInteger(userId) || userId <= 0 || !paidTiers.has(tier)
      || !Number.isSafeInteger(durationDays) || durationDays <= 0 || durationDays > 3660
      || !/^virtualpay:[A-Za-z0-9_-]{1,128}$/.test(eventKey)) {
      throw new TypeError("Payment entitlement request is invalid");
    }
    return this.entitlements.grantOnce(userId, tier, durationDays, eventKey);
  }
}
