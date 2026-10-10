import type { PaymentCorePort } from "../interfaces/payment-core-port.js";
import type { PaymentEnvironment } from "../interfaces/payment-environment.js";
import type { WechatOrderQueryResult, WechatPaymentPort } from "../interfaces/wechat-payment-port.js";

const QUERY_ORDER_URI = "/xpay/query_order";
const encoder = new TextEncoder();
let cachedAccessToken: { value: string; expiresAt: number } | null = null;

export class PaymentProviderError extends Error {
  constructor(message = "微信虚拟支付查单暂不可用") {
    super(message);
    this.name = "PaymentProviderError";
  }
}

async function paySignature(appKey: string, body: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    "raw", encoder.encode(appKey), { name: "HMAC", hash: "SHA-256" }, false, ["sign"],
  );
  const signed = await crypto.subtle.sign("HMAC", key, encoder.encode(`${QUERY_ORDER_URI}&${body}`));
  return Array.from(new Uint8Array(signed), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

function record(value: unknown): Record<string, unknown> | null {
  return value && typeof value === "object" && !Array.isArray(value)
    ? value as Record<string, unknown> : null;
}

export class WechatVirtualPayMapper implements WechatPaymentPort {
  constructor(
    private readonly env: PaymentEnvironment,
    private readonly core: PaymentCorePort,
  ) {}

  async queryOrder(openid: string, outTradeNo: string, appKey: string): Promise<WechatOrderQueryResult> {
    try {
      const accessToken = await this.accessToken();
      const body = JSON.stringify({ openid, env: 0, order_id: outTradeNo });
      const signature = await paySignature(appKey, body);
      const endpoint = this.endpoint(QUERY_ORDER_URI);
      endpoint.searchParams.set("access_token", accessToken);
      endpoint.searchParams.set("pay_sig", signature);
      const response = await fetch(endpoint, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body,
        redirect: "manual",
        signal: AbortSignal.timeout(8_000),
      });
      if (!response.ok) throw new PaymentProviderError();
      const root = record(await response.json());
      if (!root || root.errcode !== 0) return { paid: false, wxOrderId: null, paidFee: 0 };
      const order = record(root.order);
      if (!order) return { paid: false, wxOrderId: null, paidFee: 0 };
      const state = typeof order.status === "number" ? order.status : Number(order.status);
      const paidFee = typeof order.paid_fee === "number" ? order.paid_fee : Number(order.paid_fee);
      const wxOrderId = typeof order.wx_order_id === "string" ? order.wx_order_id : null;
      return {
        paid: [2, 3, 4].includes(state),
        wxOrderId,
        paidFee: Number.isSafeInteger(paidFee) && paidFee >= 0 ? paidFee : 0,
      };
    } catch (error) {
      if (error instanceof PaymentProviderError) throw error;
      throw new PaymentProviderError();
    }
  }

  private async accessToken(): Promise<string> {
    if (cachedAccessToken && cachedAccessToken.expiresAt > Date.now() + 60_000) {
      return cachedAccessToken.value;
    }
    let credentials;
    try {
      credentials = await this.core.resolveWechatCredential();
    } catch {
      throw new PaymentProviderError("微信小程序凭据未配置");
    }
    const endpoint = this.endpoint("/cgi-bin/token");
    endpoint.searchParams.set("grant_type", "client_credential");
    endpoint.searchParams.set("appid", credentials.appId);
    endpoint.searchParams.set("secret", credentials.appSecret);
    try {
      const response = await fetch(endpoint, { signal: AbortSignal.timeout(8_000), redirect: "manual" });
      if (!response.ok) throw new PaymentProviderError();
      const root = record(await response.json());
      const token = typeof root?.access_token === "string" ? root.access_token : "";
      const expiresIn = typeof root?.expires_in === "number" ? root.expires_in : Number(root?.expires_in);
      if (!token || !Number.isSafeInteger(expiresIn) || expiresIn <= 0) throw new PaymentProviderError();
      cachedAccessToken = { value: token, expiresAt: Date.now() + expiresIn * 1000 };
      return token;
    } catch (error) {
      if (error instanceof PaymentProviderError) throw error;
      throw new PaymentProviderError();
    }
  }

  private endpoint(path: string): URL {
    let base: URL;
    try {
      base = new URL(this.env.WECHAT_VIRTUAL_PAY_API_BASE_URL || "https://api.weixin.qq.com");
    } catch {
      throw new PaymentProviderError();
    }
    if (base.protocol !== "https:" || base.hostname !== "api.weixin.qq.com"
      || base.username || base.password || base.search || base.hash) throw new PaymentProviderError();
    return new URL(path, base);
  }
}
