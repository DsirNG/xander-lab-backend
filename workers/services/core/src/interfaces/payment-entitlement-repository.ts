export interface PaymentEntitlementRepository {
  grantOnce(userId: number, tier: string, durationDays: number, eventKey: string): Promise<boolean>;
}
