import { decryptSecret } from "./secret-service.js";
import { encryptSecret } from "./secret-service.js";
import type { WechatCredentialRepository } from "../interfaces/wechat-credential-repository.js";

export type ResolvedWechatCredential =
  | { kind: "configured"; appId: string; appSecret: string }
  | { kind: "unconfigured" }
  | { kind: "invalid" };

export class WechatCredentialService {
  constructor(private readonly credentials: WechatCredentialRepository) {}

  async adminView(): Promise<{ appId: string | null; secretConfigured: boolean; updatedAt: string | null }> {
    const stored = await this.credentials.findWechatCredential();
    return stored
      ? { appId: stored.appId, secretConfigured: Boolean(stored.encryptedAppSecret), updatedAt: stored.updatedAt }
      : { appId: null, secretConfigured: false, updatedAt: null };
  }

  async saveAdminCredential(
    appId: string,
    appSecret: string,
    encryptionKey?: string,
  ): Promise<{ kind: "saved"; view: { appId: string; secretConfigured: boolean; updatedAt: string } }
    | { kind: "unconfigured" | "save_failed" }> {
    const encryptedAppSecret = await encryptSecret(appSecret, encryptionKey);
    if (!encryptedAppSecret) return { kind: "unconfigured" };
    const saved = await this.credentials.saveWechatCredential(appId, encryptedAppSecret);
    return saved
      ? { kind: "saved", view: { appId: saved.appId, secretConfigured: true, updatedAt: saved.updatedAt } }
      : { kind: "save_failed" };
  }

  deleteAdminCredential(): Promise<void> {
    return this.credentials.deleteWechatCredential();
  }

  async resolve(
    fallbackAppId: string | undefined,
    fallbackAppSecret: string | undefined,
    encryptionKey: string | undefined,
  ): Promise<ResolvedWechatCredential> {
    const stored = encryptionKey ? await this.credentials.findWechatCredential() : null;
    if (stored) {
      const appSecret = await decryptSecret(stored.encryptedAppSecret, encryptionKey);
      return appSecret
        ? { kind: "configured", appId: stored.appId, appSecret }
        : { kind: "invalid" };
    }
    return fallbackAppId && fallbackAppSecret
      ? { kind: "configured", appId: fallbackAppId, appSecret: fallbackAppSecret }
      : { kind: "unconfigured" };
  }
}
