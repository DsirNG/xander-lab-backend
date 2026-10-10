export interface BillingTokenPrice {
  inputMilliPer1kToken: number;
  outputMilliPer1kToken: number;
  cachedMilliPer1kToken: number;
  cachedWriteMilliPer1kToken: number;
}

export interface BillingModelPrice extends BillingTokenPrice {
  id: number;
  model: string;
  enabled: number;
  remark: string | null;
  updatedAt: string;
}

export interface BillingLedgerInput {
  userId: number;
  bizType: string;
  bizKey: string;
  bizRef: string | null;
  amount: number;
  balanceDelta: number;
  unitPrice: string;
  model: string | null;
  inputTokens: number | null;
  outputTokens: number | null;
  cachedTokens: number | null;
  remark: string | null;
}

export interface BillingModelPriceInput extends BillingTokenPrice {
  model: string;
  enabled: boolean;
  remark: string | null;
}

export interface BillingRepository {
  findModelPrice(model: string): Promise<BillingModelPrice | null>;
  listModelPrices(): Promise<BillingModelPrice[]>;
  deleteModelPrice(model: string): Promise<void>;
  upsertModelPrice(input: BillingModelPriceInput): Promise<BillingModelPrice | null>;
  appendLedger(input: BillingLedgerInput): Promise<void>;
  grantRegistrationPoints(userId: number, amount: number, unitPrice: string, enabled: boolean): Promise<void>;
  findBalance(userId: number): Promise<number>;
  findConsumedToday(userId: number): Promise<number>;
  pageLedger(userId: number, size: number, offset: number): Promise<{ records: Record<string, unknown>[]; total: number }>;
  grantByKey(userId: number, bizKey: string, amount: number, unitPrice: string): Promise<void>;
  transferBalance(fromUserId: number, toUserId: number, bizKey: string, unitPrice: string, remark: string): Promise<void>;
}
