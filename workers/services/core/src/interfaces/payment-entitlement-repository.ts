export interface PaymentEntitlementRepository {
  refundPoints?(action: "quote" | "reserve" | "complete" | "release", userId: number, eventKey: string, refundId: string): Promise<unknown>;
  subscription?(userId: number): Promise<{ tier: string; tierExpiresAt: string | null } | null>;
  grantPoints?(userId: number, amount: number, eventKey: string): Promise<boolean>;
  grantOnce(userId: number, tier: string, durationDays: number, eventKey: string): Promise<boolean>;
}
