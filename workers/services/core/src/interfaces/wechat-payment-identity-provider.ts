export type WechatPaymentIdentityExchange =
  | { kind: "success"; openid: string; sessionKey: string }
  | { kind: "invalid_code" }
  | { kind: "invalid_configuration" }
  | { kind: "unavailable" };

export interface WechatPaymentIdentityProvider {
  exchangePaymentCode(
    appId: string,
    appSecret: string,
    code: string,
    configuredEndpoint?: string,
  ): Promise<WechatPaymentIdentityExchange>;
}
