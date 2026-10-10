import type {
  PlatformCredentialRecord,
  PlatformCredentialRepository,
  PublishingPlatform,
} from "../interfaces/platform-credential-repository.js";

const encoder = new TextEncoder();
const decoder = new TextDecoder("utf-8", { fatal: true });
const CSDN_FIELDS = new Set(["cookie", "username"]);
const JUEJIN_FIELDS = new Set([
  "cookie", "csrfToken", "aid", "uuid", "juejinUserId", "secUserId", "username",
  "avatarUrl", "mobile", "sessionKey",
]);

function toArrayBuffer(value: Uint8Array): ArrayBuffer {
  const buffer = new ArrayBuffer(value.byteLength);
  new Uint8Array(buffer).set(value);
  return buffer;
}

export class PlatformCredentialError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
    this.name = "PlatformCredentialError";
  }
}

export class PlatformCredentialService {
  constructor(
    private readonly credentials: PlatformCredentialRepository,
    private readonly encryptionKey: string | undefined,
  ) {}

  async status(userId: number, platform: PublishingPlatform) {
    const record = await this.credentials.find(userId, platform);
    const status = this.statusFor(record, platform);
    return { status, qrCodeDataUrl: null, expiresInSeconds: null, queuePosition: null, estimatedWaitSeconds: null };
  }

  async isAuthorized(userId: number, platform: PublishingPlatform): Promise<boolean> {
    const record = await this.credentials.find(userId, platform);
    return this.statusFor(record, platform) === "AUTHORIZED";
  }

  async save(userId: number, platform: PublishingPlatform, credential: unknown, accountName: string | null): Promise<void> {
    if (!Number.isSafeInteger(userId) || userId <= 0) throw new PlatformCredentialError(400, "凭证属主参数非法");
    const normalized = this.validateCredential(platform, credential, accountName);
    const encryptedCredential = await this.encrypt(userId, platform, normalized);
    await this.credentials.save({ userId, platform, encryptedCredential, accountName });
  }

  async require(userId: number, platform: PublishingPlatform): Promise<Record<string, string>> {
    const record = await this.credentials.find(userId, platform);
    if (!record || this.statusFor(record, platform) !== "AUTHORIZED") {
      throw new PlatformCredentialError(401, "平台授权已失效，请重新授权");
    }
    try {
      const plaintext = await this.decrypt(userId, platform, record.encryptedCredential);
      const value: unknown = JSON.parse(decoder.decode(plaintext));
      if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("invalid credential");
      return value as Record<string, string>;
    } catch {
      throw new PlatformCredentialError(503, "平台凭证无法读取，请重新授权");
    }
  }

  delete(userId: number, platform: PublishingPlatform): Promise<void> {
    return this.credentials.delete(userId, platform);
  }

  markExpired(userId: number, platform: PublishingPlatform): Promise<void> {
    return this.credentials.markExpired(userId, platform);
  }

  async protectAuthorizationSession(userId: number, platform: PublishingPlatform, sessionId: string): Promise<string> {
    return this.encryptOpaque(userId, platform, sessionId, `platform-authorization-session:v1:${platform}:${userId}`);
  }

  async revealAuthorizationSession(userId: number, platform: PublishingPlatform, encrypted: string): Promise<string> {
    const plaintext = await this.decryptOpaque(
      userId, platform, encrypted, `platform-authorization-session:v1:${platform}:${userId}`,
    );
    const sessionId = decoder.decode(plaintext);
    if (!/^[A-Za-z0-9._-]{1,128}$/.test(sessionId)) throw new PlatformCredentialError(503, "平台授权会话无法读取");
    return sessionId;
  }

  private statusFor(record: PlatformCredentialRecord | null, platform: PublishingPlatform) {
    if (!record) return "NOT_AUTHORIZED" as const;
    if (record.status === "EXPIRED") return "EXPIRED" as const;
    if (record.status !== "ACTIVE" || !record.encryptedCredential
      || (platform === "csdn" && !record.accountName)) return "NOT_AUTHORIZED" as const;
    return "AUTHORIZED" as const;
  }

  private validateCredential(platform: PublishingPlatform, value: unknown, accountName: string | null): Record<string, string> {
    if (!value || typeof value !== "object" || Array.isArray(value)) {
      throw new PlatformCredentialError(400, "平台凭证格式不正确");
    }
    const fields = platform === "csdn" ? CSDN_FIELDS : JUEJIN_FIELDS;
    const entries = Object.entries(value as Record<string, unknown>);
    if (entries.length === 0 || entries.length > fields.size
      || entries.some(([key, item]) => !fields.has(key) || typeof item !== "string" || item.length > 16_384)) {
      throw new PlatformCredentialError(400, "平台凭证格式不正确");
    }
    const credential = Object.fromEntries(entries.map(([key, item]) => [key, (item as string).trim()]));
    if (["cookie", "csrfToken"].some((key) => credential[key] && /[\r\n]/.test(credential[key]!))) {
      throw new PlatformCredentialError(400, "平台凭证格式不正确");
    }
    if (!credential.cookie || (platform === "csdn" && (!credential.username || !accountName?.trim()))) {
      throw new PlatformCredentialError(400, "平台凭证信息不完整");
    }
    if (accountName !== null && (typeof accountName !== "string" || accountName.trim().length > 200)) {
      throw new PlatformCredentialError(400, "平台账号名称格式不正确");
    }
    return credential;
  }

  private async encrypt(userId: number, platform: PublishingPlatform, value: Record<string, string>): Promise<string> {
    return this.encryptOpaque(userId, platform, JSON.stringify(value), `platform-credential:v1:${platform}:${userId}`);
  }

  private async encryptOpaque(userId: number, platform: PublishingPlatform, value: string, context: string): Promise<string> {
    const key = await this.importKey();
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const additionalData = encoder.encode(context);
    const ciphertext = await crypto.subtle.encrypt(
      { name: "AES-GCM", iv, additionalData, tagLength: 128 }, key, encoder.encode(value),
    );
    return `v1.${this.toBase64Url(iv)}.${this.toBase64Url(new Uint8Array(ciphertext))}`;
  }

  private async decrypt(userId: number, platform: PublishingPlatform, value: string): Promise<Uint8Array> {
    return this.decryptOpaque(userId, platform, value, `platform-credential:v1:${platform}:${userId}`);
  }

  private async decryptOpaque(userId: number, platform: PublishingPlatform, value: string, context: string): Promise<Uint8Array> {
    const [version, encodedIv, encodedCiphertext, ...extra] = value.split(".");
    if (version !== "v1" || !encodedIv || !encodedCiphertext || extra.length > 0) throw new Error("invalid ciphertext");
    return new Uint8Array(await crypto.subtle.decrypt({
      name: "AES-GCM",
      iv: toArrayBuffer(this.fromBase64Url(encodedIv)),
      additionalData: encoder.encode(context),
      tagLength: 128,
    }, await this.importKey(), toArrayBuffer(this.fromBase64Url(encodedCiphertext))));
  }

  private async importKey(): Promise<CryptoKey> {
    const encoded = this.encryptionKey?.trim();
    if (!encoded) throw new PlatformCredentialError(503, "平台凭证加密密钥尚未配置");
    let raw: Uint8Array;
    try {
      const binary = atob(encoded);
      raw = Uint8Array.from(binary, (character) => character.charCodeAt(0));
    } catch {
      throw new PlatformCredentialError(503, "平台凭证加密密钥配置无效");
    }
    if (raw.byteLength !== 32) throw new PlatformCredentialError(503, "平台凭证加密密钥配置无效");
    return crypto.subtle.importKey("raw", toArrayBuffer(raw), "AES-GCM", false, ["encrypt", "decrypt"]);
  }

  private toBase64Url(value: Uint8Array): string {
    let binary = "";
    for (const byte of value) binary += String.fromCharCode(byte);
    return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/, "");
  }

  private fromBase64Url(value: string): Uint8Array {
    if (!/^[A-Za-z0-9_-]+$/.test(value)) throw new Error("invalid base64url");
    const base64 = value.replaceAll("-", "+").replaceAll("_", "/");
    const binary = atob(base64 + "=".repeat((4 - base64.length % 4) % 4));
    return Uint8Array.from(binary, (character) => character.charCodeAt(0));
  }
}
