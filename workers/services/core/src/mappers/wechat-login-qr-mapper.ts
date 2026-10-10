import type { RedisEnvironment } from "../redis.js";
import { readEphemeralJson, saveEphemeralJson } from "../redis.js";
import type { WechatLoginQrRepository } from "../interfaces/wechat-login-qr-repository.js";
import type { WechatCredentialService } from "../services/wechat-credential-service.js";

interface WechatLoginQrEnvironment extends RedisEnvironment {
  WECHAT_MINIAPP_APP_ID?: string;
  WECHAT_MINIAPP_APP_SECRET?: string;
  WECHAT_MINIAPP_LOGIN_PAGE?: string;
  WECHAT_MINIAPP_ENV_VERSION?: string;
  WECHAT_CREDENTIAL_ENCRYPTION_KEY?: string;
}

interface CachedAccessToken extends Record<string, unknown> {
  accessToken?: unknown;
  expiresAt?: unknown;
}

export class WechatLoginQrMapper implements WechatLoginQrRepository {
  constructor(
    private readonly env: WechatLoginQrEnvironment,
    private readonly credentials: WechatCredentialService,
  ) {}

  async createLoginQr(ticket: string): Promise<string | null> {
    const credential = await this.credentials.resolve(
      this.env.WECHAT_MINIAPP_APP_ID,
      this.env.WECHAT_MINIAPP_APP_SECRET,
      this.env.WECHAT_CREDENTIAL_ENCRYPTION_KEY,
    );
    if (credential.kind !== "configured") return null;

    const accessToken = await this.getAccessToken(credential.appId, credential.appSecret);
    if (!accessToken) return null;
    try {
      const endpoint = new URL("https://api.weixin.qq.com/wxa/getwxacodeunlimit");
      endpoint.searchParams.set("access_token", accessToken);
      const response = await fetch(endpoint, {
        method: "POST",
        headers: { "content-type": "application/json", accept: "image/png,image/jpeg,application/json" },
        body: JSON.stringify({
          scene: ticket,
          page: this.env.WECHAT_MINIAPP_LOGIN_PAGE || "pages/scan-login/index",
          check_path: false,
          env_version: ["develop", "trial", "release"].includes(this.env.WECHAT_MINIAPP_ENV_VERSION ?? "trial")
            ? this.env.WECHAT_MINIAPP_ENV_VERSION ?? "trial"
            : "trial",
        }),
        signal: AbortSignal.timeout(10_000),
      });
      const contentType = response.headers.get("content-type")?.toLowerCase() ?? "";
      if (!response.ok || contentType.includes("json")) return null;
      const image = await readBoundedResponse(response, 1_048_576);
      if (!image || image.byteLength < 100) return null;
      const png = image.length >= 8 && image[0] === 0x89 && image[1] === 0x50
        && image[2] === 0x4e && image[3] === 0x47;
      const jpeg = image.length >= 3 && image[0] === 0xff && image[1] === 0xd8 && image[2] === 0xff;
      if (!png && !jpeg) return null;
      const mime = contentType.includes("image/jpeg") || jpeg ? "image/jpeg" : "image/png";
      return `data:${mime};base64,${encodeBase64(image)}`;
    } catch {
      return null;
    }
  }

  private async getAccessToken(appId: string, appSecret: string): Promise<string | null> {
    const cached = await readEphemeralJson<CachedAccessToken>(this.env, "wechat-access-token", appId);
    if (typeof cached?.accessToken === "string" && typeof cached.expiresAt === "number"
      && cached.expiresAt > Date.now() + 60_000) return cached.accessToken;
    try {
      const endpoint = new URL("https://api.weixin.qq.com/cgi-bin/token");
      endpoint.searchParams.set("grant_type", "client_credential");
      endpoint.searchParams.set("appid", appId);
      endpoint.searchParams.set("secret", appSecret);
      const response = await fetch(endpoint, { signal: AbortSignal.timeout(8_000) });
      if (!response.ok) return null;
      const payload: unknown = await response.json();
      if (!payload || typeof payload !== "object" || Array.isArray(payload)) return null;
      const body = payload as Record<string, unknown>;
      if (typeof body.access_token !== "string" || !Number.isSafeInteger(body.expires_in)) return null;
      const ttl = Math.max(60, Number(body.expires_in) - 300);
      const expiresAt = Date.now() + ttl * 1000;
      await saveEphemeralJson(this.env, "wechat-access-token", appId, {
        accessToken: body.access_token,
        expiresAt,
      }, ttl);
      return body.access_token;
    } catch {
      return null;
    }
  }
}

async function readBoundedResponse(response: Response, maximumBytes: number): Promise<Uint8Array | null> {
  const length = response.headers.get("content-length");
  if (length && Number(length) > maximumBytes) return null;
  if (!response.body) return null;
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    while (true) {
      const result = await reader.read();
      if (result.done) break;
      size += result.value.byteLength;
      if (size > maximumBytes) {
        await reader.cancel();
        return null;
      }
      chunks.push(result.value);
    }
  } finally {
    reader.releaseLock();
  }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return bytes;
}

function encodeBase64(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}
