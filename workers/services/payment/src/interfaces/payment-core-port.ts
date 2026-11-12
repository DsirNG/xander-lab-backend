export interface PaymentWechatIdentity {
  openid: string;
  sessionKey: string;
}

export interface PaymentWechatCredential {
  appId: string;
  appSecret: string;
}

export class PaymentCoreError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
    this.name = "PaymentCoreError";
  }
}

export interface RefundPointsResult { original: number; remaining: number; amount?: number; state?: string }
export interface PaymentCorePort {
  refundPoints?(action: "quote"|"reserve"|"complete"|"release",userId: number,eventKey: string,refundId: string): Promise<RefundPointsResult|boolean|null>;
  subscription(userId: number): Promise<{ tier: string; tierExpiresAt: string | null; active: boolean }>;
  grantPoints(input: { userId: number; points: number; eventKey: string }): Promise<void>;
  resolveWechatIdentity(userId: number, loginCode: string): Promise<PaymentWechatIdentity>;
  resolveWechatCredential(): Promise<PaymentWechatCredential>;
  grantEntitlement(input: {
    userId: number;
    tier: string;
    durationDays: number;
    eventKey: string;
  }): Promise<void>;
}
