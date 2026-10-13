import type { PlatformAccountPort } from "../interfaces/platform-account-port.js";
import { CsdnProviderError } from "../interfaces/csdn-publication-port.js";
import { JuejinCatalogProviderError } from "../interfaces/juejin-catalog-port.js";
import { PlatformCredentialError, type PlatformCredentialService } from "./platform-credential-service.js";

/** Checks the owner's saved credential against the platform's authenticated account API. */
export class PlatformConnectionService {
  constructor(private readonly credentials: PlatformCredentialService, private readonly accounts: PlatformAccountPort) {}

  async testConnection(userId: number, platform: "csdn" | "juejin" | "wechat_mp" = "csdn",
    wechatProfile?: { nickname: string | null; avatarUrl: string | null }) {
    if (platform === "wechat_mp") {
      if (wechatProfile) return this.credentials.saveWechatAccountProfile(userId, { username: null, ...wechatProfile });
      const connection = await this.credentials.status(userId, platform);
      if (connection.status !== "AUTHORIZED") {
        throw new PlatformCredentialError(409, "微信公众号授权已失效，请重新绑定");
      }
      if (!connection.profile) {
        throw new PlatformCredentialError(404, "暂未读取到微信公众号账号资料，请重新绑定");
      }
      return { ...connection.profile, authorizedAt: connection.authorizedAt };
    }

    const name = platform === "csdn" ? "CSDN" : "掘金";
    let credential: Record<string, string>;
    let reference: string;
    let authorizedAt: string | null;
    try {
      const saved = await this.credentials.requireForVerification(userId, platform);
      ({ credential, reference, authorizedAt } = saved);
    } catch (error) {
      // Platform expiry must not trigger the site's own login refresh/logout flow.
      if (error instanceof PlatformCredentialError && error.status === 409) {
        throw new PlatformCredentialError(409, `请先重新授权 ${name}`);
      }
      throw error;
    }
    try {
      const profile = await this.accounts.getCurrentAccount(credential);
      const verifiedAt = new Date().toISOString();
      await this.credentials.saveAccountProfile(userId, platform, reference, profile, verifiedAt);
      return { ...profile, verifiedAt, authorizedAt };
    } catch (error) {
      if (error instanceof CsdnProviderError || error instanceof JuejinCatalogProviderError) {
        throw new PlatformCredentialError(error.sessionExpired ? 409 : 502,
          error.sessionExpired ? `${name} 授权已失效，请重新授权` : `${name} 连接验证失败，请稍后重试或检查接口配置`);
      }
      throw error;
    }
  }
}
