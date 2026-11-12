import type { PaymentEntitlementRepository } from "../interfaces/payment-entitlement-repository.js";

import { effectiveIdentityTier } from "./identity-tier.js";

const paidTiers = new Set(["PLUS", "PRO", "ULTRA"]);

export class PaymentEntitlementService {
  constructor(private readonly entitlements: PaymentEntitlementRepository) {}

  async refundPoints(action: string, userId: number, eventKey: string, refundId: string) {
    if (!Number.isSafeInteger(userId) || userId < 1 || !/^virtualpay:[A-Za-z0-9_-]{1,128}$/.test(eventKey)
      || !["quote","reserve","complete","release"].includes(action)
      || (action !== "quote" && !/^[A-Za-z0-9_-]{8,32}$/.test(refundId))) throw new TypeError("Invalid refund request");
    if (!this.entitlements.refundPoints) throw new Error("Refund repository unavailable");
    return this.entitlements.refundPoints(action as "quote" | "reserve" | "complete" | "release",userId,eventKey,refundId);
  }

  async subscription(userId: number) {
    const user = await this.entitlements.subscription?.(userId);
    if (!user) throw new TypeError("User is unavailable");
    const tier = effectiveIdentityTier(user);
    return { tier, tierExpiresAt: user.tierExpiresAt, active: tier !== "FREE" };
  }

  async grantPoints(userId: number, points: number, eventKey: string) {
    if (
      !Number.isSafeInteger(userId) ||
      userId <= 0 ||
      !Number.isSafeInteger(points) ||
      points < 10 ||
      points > 10000 ||
      points % 10 !== 0 ||
      !/^virtualpay:[A-Za-z0-9_-]{1,128}$/.test(eventKey)
    )
      throw new TypeError("Points purchase is invalid");
    if (!this.entitlements.grantPoints) throw new Error("Points repository unavailable");
    return this.entitlements.grantPoints(userId, points * 1000, eventKey);
  }

  async grantOnce(userId: number, tier: string, durationDays: number, eventKey: string): Promise<boolean> {
    if (
      !Number.isSafeInteger(userId) ||
      userId <= 0 ||
      !paidTiers.has(tier) ||
      !Number.isSafeInteger(durationDays) ||
      durationDays <= 0 ||
      durationDays > 3660 ||
      !/^virtualpay:[A-Za-z0-9_-]{1,128}$/.test(eventKey)
    ) {
      throw new TypeError("Payment entitlement request is invalid");
    }
    return this.entitlements.grantOnce(userId, tier, durationDays, eventKey);
  }
}
