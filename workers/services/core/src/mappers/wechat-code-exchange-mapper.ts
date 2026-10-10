import type {
  WechatCodeExchangeRepository,
  WechatCodeExchangeResult,
} from "../interfaces/wechat-code-exchange-repository.js";
import type {
  WechatPaymentIdentityExchange,
  WechatPaymentIdentityProvider,
} from "../interfaces/wechat-payment-identity-provider.js";

export class WechatCodeExchangeMapper implements WechatCodeExchangeRepository, WechatPaymentIdentityProvider {
  async exchangeCode(
    appId: string,
    appSecret: string,
    code: string,
    configuredEndpoint?: string,
  ): Promise<WechatCodeExchangeResult> {
    let endpoint: URL;
    try {
      endpoint = new URL(configuredEndpoint || "https://api.weixin.qq.com/sns/jscode2session");
    } catch {
      return { kind: "invalid_configuration" };
    }
    if (endpoint.protocol !== "https:" || endpoint.hostname !== "api.weixin.qq.com") {
      return { kind: "invalid_configuration" };
    }
    endpoint.searchParams.set("appid", appId);
    endpoint.searchParams.set("secret", appSecret);
    endpoint.searchParams.set("js_code", code);
    endpoint.searchParams.set("grant_type", "authorization_code");
    try {
      const response = await fetch(endpoint, { signal: AbortSignal.timeout(8_000) });
      if (!response.ok) return { kind: "unavailable" };
      const payload: unknown = await response.json();
      if (!payload || typeof payload !== "object" || Array.isArray(payload)) {
        return { kind: "unavailable" };
      }
      const body = payload as Record<string, unknown>;
      if (body.errcode !== undefined && body.errcode !== 0) return { kind: "invalid_code" };
      return typeof body.openid === "string" && body.openid.length <= 128
        ? { kind: "success", openid: body.openid }
        : { kind: "invalid_code" };
    } catch {
      return { kind: "unavailable" };
    }
  }

  async exchangePaymentCode(
    appId: string,
    appSecret: string,
    code: string,
    configuredEndpoint?: string,
  ): Promise<WechatPaymentIdentityExchange> {
    let endpoint: URL;
    try {
      endpoint = new URL(configuredEndpoint || "https://api.weixin.qq.com/sns/jscode2session");
    } catch {
      return { kind: "invalid_configuration" };
    }
    if (endpoint.protocol !== "https:" || endpoint.hostname !== "api.weixin.qq.com") {
      return { kind: "invalid_configuration" };
    }
    endpoint.searchParams.set("appid", appId);
    endpoint.searchParams.set("secret", appSecret);
    endpoint.searchParams.set("js_code", code);
    endpoint.searchParams.set("grant_type", "authorization_code");
    try {
      const response = await fetch(endpoint, { signal: AbortSignal.timeout(8_000) });
      if (!response.ok) return { kind: "unavailable" };
      const payload: unknown = await response.json();
      if (!payload || typeof payload !== "object" || Array.isArray(payload)) {
        return { kind: "unavailable" };
      }
      const body = payload as Record<string, unknown>;
      if (body.errcode !== undefined && body.errcode !== 0) return { kind: "invalid_code" };
      if (typeof body.openid !== "string" || body.openid.length > 128
        || typeof body.session_key !== "string" || body.session_key.length === 0
        || body.session_key.length > 256) return { kind: "invalid_code" };
      return { kind: "success", openid: body.openid, sessionKey: body.session_key };
    } catch {
      return { kind: "unavailable" };
    }
  }
}
