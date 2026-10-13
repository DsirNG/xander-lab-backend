import type { PlatformEnvironment } from "../interfaces/platform-environment.js";
import { PlatformBlogSyncError, type PlatformBlogSyncPort } from "../interfaces/platform-blog-sync.js";
import { fetchWithServiceBinding } from "@dinqor/worker-runtime";

const MAX_RESPONSE_BYTES = 16 * 1024;

/** Delegates public sync through Content's owner-checked publication ledger over HTTPS. */
export class ContentBlogPublicationMapper implements PlatformBlogSyncPort {
  constructor(private readonly env: PlatformEnvironment, private readonly fetcher: typeof fetch = fetch) {}

  async sync(input: Parameters<PlatformBlogSyncPort["sync"]>[0]) {
    const base = this.env.CONTENT_AUTOMATION_SERVICE_URL?.trim();
    const token = this.env.CONTENT_AUTOMATION_SERVICE_TOKEN;
    if (!base || !token) throw new PlatformBlogSyncError(503, "Content 发布服务尚未配置");

    let url: URL;
    try {
      url = new URL(`/internal/agent/publications/${input.postId}/sync/${input.platform}`, base);
    } catch {
      throw new PlatformBlogSyncError(503, "Content 发布服务地址无效");
    }
    if (url.username || url.password || url.search || url.hash
      || (url.protocol !== "https:" && !(url.protocol === "http:" && ["127.0.0.1", "localhost"].includes(url.hostname)))) {
      throw new PlatformBlogSyncError(503, "Content 发布服务必须使用 HTTPS");
    }

    let response: Response;
    try {
      response = await fetchWithServiceBinding(this.env.CONTENT_AUTOMATION_SERVICE, url, {
        method: "POST",
        headers: { "content-type": "application/json", "x-dinqor-service-token": token },
        body: JSON.stringify({
          userId: input.userId,
          postId: input.postId,
          ...(input.platform === "juejin" ? {
            juejinCategoryId: input.juejinCategoryId,
            juejinTagIds: input.juejinTagIds,
          } : {}),
        }),
        redirect: "manual",
        signal: AbortSignal.timeout(30_000),
      }, this.fetcher);
    } catch {
      throw new PlatformBlogSyncError(503, "站外同步结果暂不可确认");
    }

    // Internal service authentication failures must not invalidate the site login.
    if (response.status === 401) {
      throw new PlatformBlogSyncError(502, "Content 发布服务鉴权失败，请检查服务凭证配置");
    }

    let payload: unknown;
    try {
      payload = await readJson(response);
    } catch {
      throw new PlatformBlogSyncError(response.ok ? 502 : response.status, "Content 发布服务响应无效");
    }
    if (!response.ok) {
      const message = isObject(payload) && typeof payload.message === "string" && payload.message.length <= 200
        ? payload.message : "站外同步暂时不可用";
      throw new PlatformBlogSyncError(response.status >= 400 && response.status <= 599 ? response.status : 502, message);
    }
    if (!isObject(payload) || payload.platform !== input.platform || typeof payload.externalId !== "string"
      || !payload.externalId.trim() || (payload.url !== null && payload.url !== undefined && typeof payload.url !== "string")) {
      throw new PlatformBlogSyncError(502, "Content 发布服务返回了无效结果");
    }
    return {
      platform: input.platform,
      externalId: payload.externalId,
      url: typeof payload.url === "string" ? payload.url : null,
    };
  }
}

async function readJson(response: Response): Promise<unknown> {
  const declared = response.headers.get("content-length");
  if (declared && (!/^\d+$/.test(declared) || Number(declared) > MAX_RESPONSE_BYTES)) {
    throw new Error("response too large");
  }
  const reader = response.body?.getReader();
  if (!reader) return null;
  const chunks: Uint8Array[] = [];
  let length = 0;
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      length += value.byteLength;
      if (length > MAX_RESPONSE_BYTES) {
        await reader.cancel();
        throw new Error("response too large");
      }
      chunks.push(value);
    }
  } finally { reader.releaseLock(); }
  const bytes = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  return JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes)) as unknown;
}

function isObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}
