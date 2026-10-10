import type {
  BillingLedgerInput,
  BillingModelPrice,
  BillingModelPriceInput,
  BillingRepository,
} from "../interfaces/billing-repository.js";

export class BillingService {
  constructor(private readonly billing: BillingRepository) {}

  findModelPrice(model: string | null): Promise<BillingModelPrice | null> {
    return model ? this.billing.findModelPrice(model) : Promise.resolve(null);
  }

  listModelPrices(): Promise<BillingModelPrice[]> {
    return this.billing.listModelPrices();
  }

  deleteModelPrice(model: string): Promise<void> {
    return this.billing.deleteModelPrice(model);
  }

  upsertModelPrice(input: BillingModelPriceInput): Promise<BillingModelPrice | null> {
    return this.billing.upsertModelPrice(input);
  }

  appendLedger(input: BillingLedgerInput): Promise<void> {
    return this.billing.appendLedger(input);
  }

  grantRegistrationPoints(userId: number, amount: number, unitPrice: string, enabled: boolean): Promise<void> {
    return this.billing.grantRegistrationPoints(userId, amount, unitPrice, enabled);
  }

  balance(userId: number): Promise<number> {
    return this.billing.findBalance(userId);
  }

  consumedToday(userId: number): Promise<number> {
    return this.billing.findConsumedToday(userId);
  }

  pageLedger(userId: number, size: number, offset: number) {
    return this.billing.pageLedger(userId, size, offset);
  }

  grantByKey(userId: number, bizKey: string, amount: number, unitPrice: string): Promise<void> {
    return this.billing.grantByKey(userId, bizKey, amount, unitPrice);
  }

  transferBalance(fromUserId: number, toUserId: number, bizKey: string, unitPrice: string, remark: string) {
    return this.billing.transferBalance(fromUserId, toUserId, bizKey, unitPrice, remark);
  }
}
