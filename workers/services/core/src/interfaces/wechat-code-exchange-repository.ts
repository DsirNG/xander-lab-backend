export type WechatCodeExchangeResult =
  | { kind: "success"; openid: string }
  | { kind: "invalid_code" }
  | { kind: "invalid_configuration" }
  | { kind: "unavailable" };

export interface WechatCodeExchangeRepository {
  exchangeCode(
    appId: string,
    appSecret: string,
    code: string,
    endpoint?: string,
  ): Promise<WechatCodeExchangeResult>;
}
