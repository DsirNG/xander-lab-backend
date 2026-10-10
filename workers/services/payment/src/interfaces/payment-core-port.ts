export interface PaymentWechatIdentity {
  openid: string;
  sessionKey: string;
}

export interface PaymentWechatCredential {
  appId: string;
  appSecret: string;
}

export class PaymentCoreError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
    this.name = "PaymentCoreError";
  }
}

export interface PaymentCorePort {
  resolveWechatIdentity(userId: number, loginCode: string): Promise<PaymentWechatIdentity>;
  resolveWechatCredential(): Promise<PaymentWechatCredential>;
  grantEntitlement(input: {
    userId: number;
    tier: string;
    durationDays: number;
    eventKey: string;
  }): Promise<void>;
}
