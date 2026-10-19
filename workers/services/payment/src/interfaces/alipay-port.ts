export type AlipayResult = Record<string, unknown>;

export class AlipayOperationError extends Error {
  constructor(readonly tradeNotFound: boolean) { super("Alipay operation not confirmed"); }
}

/** Public-key-mode Alipay adapter. All remote results must pass SDK verification. */
export interface AlipayPort {
  page(order: { outTradeNo: string; priceCents: number; subject: string }): string;
  call(method: string, bizContent: Record<string, string>): Promise<AlipayResult>;
  verifyNotification(params: Record<string, string>): boolean;
}

export interface AlipayProduct {
  productId: string;
  tier: "PLUS" | "PRO" | "ULTRA";
  priceCents: number;
  durationDays: number;
}

export interface AlipayConfiguration {
  environment: "sandbox" | "production";
  appId: string;
  sellerId: string;
  privateKey: string;
  publicKey: string;
  gateway: string;
  returnUrl: string;
  notifyUrl?: string;
  products: AlipayProduct[];
}
