import type { WechatCodeExchangeRepository } from "../interfaces/wechat-code-exchange-repository.js";
import type { WechatPendingLoginRepository } from "../interfaces/wechat-pending-login-repository.js";
import type { WechatCredentialService } from "./wechat-credential-service.js";

export type WechatLoginExchangeResult =
  | { kind: "success"; openid: string }
  | { kind: "credential_invalid" }
  | { kind: "unconfigured" }
  | { kind: "invalid_code" }
  | { kind: "invalid_configuration" }
  | { kind: "unavailable" };

const PENDING_BIND_TTL_SECONDS = 600;

export class WechatLoginService {
  constructor(
    private readonly credentials: WechatCredentialService,
    private readonly provider: WechatCodeExchangeRepository,
    private readonly pendingLogins: WechatPendingLoginRepository,
  ) {}

  async exchangeCode(
    code: string,
    options: {
      fallbackAppId?: string;
      fallbackAppSecret?: string;
      encryptionKey?: string;
      endpoint?: string;
    },
  ): Promise<WechatLoginExchangeResult> {
    const credential = await this.credentials.resolve(
      options.fallbackAppId,
      options.fallbackAppSecret,
      options.encryptionKey,
    );
    if (credential.kind === "invalid") return { kind: "credential_invalid" };
    if (credential.kind === "unconfigured") return { kind: "unconfigured" };
    if (!code.trim() || code.length > 512) return { kind: "invalid_code" };
    return this.provider.exchangeCode(
      credential.appId,
      credential.appSecret,
      code,
      options.endpoint,
    );
  }

  async createPendingBinding(openid: string): Promise<string> {
    const token = randomHex(32);
    await this.pendingLogins.save(token, openid, PENDING_BIND_TTL_SECONDS);
    return token;
  }

  async resolvePendingBinding(token: string): Promise<string | null> {
    const pending = await this.pendingLogins.find(token);
    return typeof pending?.openid === "string" ? pending.openid : null;
  }

  deletePendingBinding(token: string): Promise<void> {
    return this.pendingLogins.delete(token);
  }
}

function randomHex(byteLength: number): string {
  const bytes = crypto.getRandomValues(new Uint8Array(byteLength));
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
}
