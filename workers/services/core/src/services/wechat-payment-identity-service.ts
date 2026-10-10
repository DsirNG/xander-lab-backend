import type { IdentitySocialRepository } from "../interfaces/identity-social-repository.js";
import type { WechatPaymentIdentityProvider } from "../interfaces/wechat-payment-identity-provider.js";
import type { WechatCredentialService } from "./wechat-credential-service.js";

export type WechatPaymentIdentityResult =
  | { kind: "success"; openid: string; sessionKey: string }
  | { kind: "unconfigured" | "credential_invalid" | "invalid_code" | "invalid_configuration" | "unavailable" | "account_disabled" | "account_unbound" | "identity_mismatch" };

export class WechatPaymentIdentityService {
  constructor(
    private readonly users: IdentitySocialRepository,
    private readonly credentials: WechatCredentialService,
    private readonly provider: WechatPaymentIdentityProvider,
  ) {}

  async resolve(
    userId: number,
    loginCode: string,
    options: {
      fallbackAppId?: string;
      fallbackAppSecret?: string;
      encryptionKey?: string;
      endpoint?: string;
    },
  ): Promise<WechatPaymentIdentityResult> {
    if (!loginCode.trim() || loginCode.length > 512) return { kind: "invalid_code" };
    const user = await this.users.findById(userId);
    if (!user || user.status !== 1) return { kind: "account_disabled" };
    if (!user.openid?.trim()) return { kind: "account_unbound" };

    const credential = await this.credentials.resolve(
      options.fallbackAppId,
      options.fallbackAppSecret,
      options.encryptionKey,
    );
    if (credential.kind === "unconfigured") return { kind: "unconfigured" };
    if (credential.kind === "invalid") return { kind: "credential_invalid" };

    const exchanged = await this.provider.exchangePaymentCode(
      credential.appId,
      credential.appSecret,
      loginCode,
      options.endpoint,
    );
    if (exchanged.kind !== "success") return exchanged;
    if (exchanged.openid !== user.openid) return { kind: "identity_mismatch" };
    return exchanged;
  }
}
