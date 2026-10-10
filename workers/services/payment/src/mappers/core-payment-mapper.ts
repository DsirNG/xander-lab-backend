import type {
  PaymentCorePort,
  PaymentWechatCredential,
  PaymentWechatIdentity,
} from "../interfaces/payment-core-port.js";
import { PaymentCoreError } from "../interfaces/payment-core-port.js";
import type { PaymentEnvironment } from "../interfaces/payment-environment.js";

interface CoreEnvelope {
  code?: unknown;
  data?: unknown;
}

export class CorePaymentMapper implements PaymentCorePort {
  constructor(private readonly env: PaymentEnvironment) {}

  async resolveWechatIdentity(userId: number, loginCode: string): Promise<PaymentWechatIdentity> {
    const data = await this.call("/internal/v1/payment/identity/resolve", { userId, loginCode });
    if (typeof data.openid !== "string" || typeof data.sessionKey !== "string") {
      throw new PaymentCoreError(503, "身份服务暂不可用");
    }
    return { openid: data.openid, sessionKey: data.sessionKey };
  }

  async resolveWechatCredential(): Promise<PaymentWechatCredential> {
    const data = await this.call("/internal/v1/payment/wechat/credentials", {});
    if (typeof data.appId !== "string" || typeof data.appSecret !== "string") {
      throw new PaymentCoreError(503, "支付服务暂不可用");
    }
    return { appId: data.appId, appSecret: data.appSecret };
  }

  async grantEntitlement(input: {
    userId: number;
    tier: string;
    durationDays: number;
    eventKey: string;
  }): Promise<void> {
    await this.call("/internal/v1/payment/entitlements/grant", input);
  }

  private async call(path: string, body: unknown): Promise<Record<string, unknown>> {
    const baseUrl = this.env.CORE_SERVICE_URL?.trim();
    const token = this.env.CORE_SERVICE_TOKEN;
    if (!baseUrl || !token) throw new PaymentCoreError(503, "身份服务暂不可用");
    let endpoint: URL;
    try {
      endpoint = new URL(path, baseUrl);
    } catch {
      throw new PaymentCoreError(503, "身份服务暂不可用");
    }
    if (endpoint.protocol !== "https:" && endpoint.hostname !== "127.0.0.1"
      && endpoint.hostname !== "localhost") throw new PaymentCoreError(503, "身份服务暂不可用");
    try {
      const request = new Request(endpoint, {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "x-dinqor-service-token": token,
        },
        body: JSON.stringify(body),
        redirect: "manual",
        signal: AbortSignal.timeout(8_000),
      });
      const response = this.env.CORE_SERVICE
        ? await this.env.CORE_SERVICE.fetch(request)
        : await fetch(request);
      let envelope: CoreEnvelope;
      try {
        const parsed: unknown = await response.json();
        if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) throw new Error("invalid envelope");
        envelope = parsed as CoreEnvelope;
      } catch {
        throw new PaymentCoreError(503, "身份服务暂不可用");
      }
      if (!response.ok || envelope.code !== 200) {
        const status = response.status === 400 || response.status === 401 ? 400 : 503;
        throw new PaymentCoreError(status, status === 400 ? "微信支付身份校验失败" : "身份服务暂不可用");
      }
      if (!envelope.data || typeof envelope.data !== "object" || Array.isArray(envelope.data)) {
        return {};
      }
      return envelope.data as Record<string, unknown>;
    } catch (error) {
      if (error instanceof PaymentCoreError) throw error;
      throw new PaymentCoreError(503, "身份服务暂不可用");
    }
  }
}
