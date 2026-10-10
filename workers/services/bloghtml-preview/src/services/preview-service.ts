import { buildPreviewPage } from "../page.js";
import type { PreviewRepository, PreviewSession } from "../interfaces/preview-repository.js";
import type { PreviewStorage } from "../interfaces/preview-storage.js";

const encoder = new TextEncoder();
const SESSION_ID_PATTERN = /^[0-9a-f]{16}$/;
const DEFAULT_TTL_SECONDS = 1800;
const DEFAULT_MAX_CODE_BYTES = 262144;
const DEFAULT_RATE_LIMIT_MAX_REQUESTS = 10;
const DEFAULT_RATE_LIMIT_WINDOW_SECONDS = 60;

export interface PreviewServiceConfig {
  internalServiceToken?: string;
  storageMode?: string;
  pageUrlPattern?: string;
  frameAncestors?: string;
  ttlSeconds?: string;
  maxCodeBytes?: string;
  rateLimitMaxRequests?: string;
  rateLimitWindowSeconds?: string;
  ossPrefix?: string;
}

export class PreviewServiceError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
    this.name = "PreviewServiceError";
  }
}

function positiveInteger(value: string | undefined, fallback: number): number {
  if (!value || !/^\d+$/.test(value)) return fallback;
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : fallback;
}

function randomSessionId(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(8));
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
}

async function sha256Hex(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", encoder.encode(value));
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

async function hmacHex(secret: string, value: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    "raw",
    encoder.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign("HMAC", key, encoder.encode(value));
  return Array.from(new Uint8Array(signature), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

/** Owns preview validation, expiry, OSS coordination, and security metadata. */
export class PreviewService {
  constructor(
    private readonly repository: PreviewRepository,
    private readonly storage: PreviewStorage,
    private readonly config: PreviewServiceConfig,
  ) {}

  async create(payload: unknown, clientIp: string, now = Date.now()): Promise<{ id: string; pageUrl: string }> {
    const mode = this.config.storageMode ?? "oss";
    if (mode !== "oss" && mode !== "local-inline") throw new PreviewServiceError(503, "预览服务配置无效");
    const pattern = this.config.pageUrlPattern ?? "https://%s.blogHTML.dinqor.cn/raw";
    if (!pattern.includes("%s")) throw new PreviewServiceError(503, "预览页面地址配置无效");

    const secret = this.config.internalServiceToken;
    if (!secret) throw new PreviewServiceError(503, "预览服务配置无效");
    const windowSeconds = positiveInteger(
      this.config.rateLimitWindowSeconds,
      DEFAULT_RATE_LIMIT_WINDOW_SECONDS,
    );
    const windowStartedAt = Math.floor(now / (windowSeconds * 1000)) * windowSeconds * 1000;
    const fingerprint = await hmacHex(secret, clientIp.trim() || "unknown");
    const requestCount = await this.repository.incrementRateLimit(fingerprint, windowStartedAt, now);
    const maximum = positiveInteger(this.config.rateLimitMaxRequests, DEFAULT_RATE_LIMIT_MAX_REQUESTS);
    if (requestCount > maximum) throw new PreviewServiceError(429, "请求过于频繁，请稍后再试");

    if (!payload || typeof payload !== "object" || Array.isArray(payload)) {
      throw new PreviewServiceError(400, "请求内容无效");
    }
    const code = (payload as { code?: unknown }).code;
    if (typeof code !== "string" || !code.trim()) throw new PreviewServiceError(400, "HTML 代码不能为空");
    const codeSize = encoder.encode(code).byteLength;
    const maxCodeBytes = Math.min(
      positiveInteger(this.config.maxCodeBytes, DEFAULT_MAX_CODE_BYTES),
      DEFAULT_MAX_CODE_BYTES,
    );
    if (codeSize > maxCodeBytes) throw new PreviewServiceError(413, "HTML 代码超过大小限制");

    const id = randomSessionId();
    const objectKey = mode === "oss" ? `${id}/page.html` : null;
    const session: PreviewSession = {
      sessionId: id,
      objectKey,
      inlineCode: mode === "local-inline" ? code : null,
      codeSize,
      contentSha256: await sha256Hex(code),
      createdAt: now,
      expiresAt: now + positiveInteger(this.config.ttlSeconds, DEFAULT_TTL_SECONDS) * 1000,
    };

    try {
      if (objectKey) await this.storage.put(objectKey, code);
      await this.repository.createSession(session);
    } catch {
      if (objectKey) {
        try {
          await this.storage.delete(objectKey);
        } catch {
          // OSS lifecycle rules remove an object if database compensation also fails.
        }
      }
      throw new PreviewServiceError(503, "预览暂不可用，请稍后重试");
    }

    return { id, pageUrl: pattern.replace("%s", id) };
  }

  async render(sessionId: string, now = Date.now()): Promise<{ html: string; frameAncestors: string }> {
    if (!SESSION_ID_PATTERN.test(sessionId)) throw new PreviewServiceError(400, "预览会话 ID 无效");
    const session = await this.repository.findActiveSession(sessionId, now);
    if (!session) throw new PreviewServiceError(404, "预览已过期或不存在");
    const code = session.objectKey
      ? await this.storage.read(session.objectKey, session.codeSize)
      : session.inlineCode;
    if (code === null) throw new PreviewServiceError(404, "预览已过期或不存在");
    if (encoder.encode(code).byteLength !== session.codeSize || await sha256Hex(code) !== session.contentSha256) {
      throw new PreviewServiceError(503, "预览内容校验失败");
    }
    return {
      html: buildPreviewPage(code),
      frameAncestors: this.config.frameAncestors ?? "https://dinqor.cn",
    };
  }

  async cleanup(now = Date.now()): Promise<void> {
    const expired = await this.repository.listExpiredSessions(now, 100);
    for (const session of expired) {
      try {
        if (session.objectKey) await this.storage.delete(session.objectKey);
        await this.repository.deleteExpiredSession(session.sessionId, now);
      } catch {
        console.warn("bloghtml-preview cleanup deferred", session.sessionId);
      }
    }
    await this.repository.deleteOldRateLimits(now - 24 * 60 * 60 * 1000);
  }
}
