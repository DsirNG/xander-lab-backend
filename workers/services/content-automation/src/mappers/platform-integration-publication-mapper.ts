import type { PlatformPublicationPort, BlogSyncResult } from "../interfaces/external-publication.js";
import { fetchWithServiceBinding, type WorkerServiceBinding } from "@dinqor/worker-runtime";

export interface PlatformPublicationEnvironment {
  PLATFORM_INTEGRATION_SERVICE_URL?: string;
  PLATFORM_INTEGRATION_SERVICE_TOKEN?: string;
  PLATFORM_INTEGRATION_SERVICE?: WorkerServiceBinding;
}

export class PlatformPublicationError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
    this.name = "PlatformPublicationError";
  }
}

const MAX_PLATFORM_RESPONSE_BYTES = 16 * 1024;

async function readJsonWithinLimit(response: Response): Promise<unknown> {
  const declaredLength = response.headers.get("content-length");
  if (declaredLength && Number(declaredLength) > MAX_PLATFORM_RESPONSE_BYTES) {
    throw new Error("Platform response too large");
  }
  if (!response.body) return null;
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let length = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      length += value.byteLength;
      if (length > MAX_PLATFORM_RESPONSE_BYTES) {
        await reader.cancel();
        throw new Error("Platform response too large");
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const bytes = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes)) as unknown;
}

export class PlatformIntegrationPublicationMapper implements PlatformPublicationPort {
  constructor(private readonly env: PlatformPublicationEnvironment) {}

  async publish(input: {
    userId: number;
    postId: number;
    platform: "csdn" | "juejin";
    juejinCategoryId?: string | null;
    juejinTagIds?: string[] | null;
    post: {
      id: number;
      title: string;
      summary: string;
      content: string;
      category: string;
      categoryName: string | null;
      coverImage: string | null;
      tags: string[];
    };
  }): Promise<BlogSyncResult> {
    const baseUrl = this.env.PLATFORM_INTEGRATION_SERVICE_URL?.trim();
    const token = this.env.PLATFORM_INTEGRATION_SERVICE_TOKEN;
    if (!baseUrl || !token) throw new PlatformPublicationError(503, "站外同步服务尚未配置内网凭证");

    let endpoint: URL;
    try {
      endpoint = new URL(
        `/internal/platform-integration/blog-posts/${input.postId}/sync/${input.platform}`,
        baseUrl,
      );
    } catch {
      throw new PlatformPublicationError(503, "站外同步服务配置无效");
    }
    if (endpoint.username || endpoint.password || endpoint.search || endpoint.hash
      || (endpoint.protocol !== "https:"
        && !(endpoint.protocol === "http:" && (endpoint.hostname === "127.0.0.1" || endpoint.hostname === "localhost")))) {
      throw new PlatformPublicationError(503, "站外同步服务必须使用 HTTPS");
    }

    let response: Response;
    try {
      response = await fetchWithServiceBinding(this.env.PLATFORM_INTEGRATION_SERVICE, endpoint, {
        method: "POST",
        headers: { "content-type": "application/json", "x-dinqor-service-token": token },
        body: JSON.stringify({
          userId: input.userId,
          postId: input.postId,
          post: input.post,
          ...(input.platform === "juejin" ? {
            juejinCategoryId: input.juejinCategoryId,
            juejinTagIds: input.juejinTagIds,
          } : {}),
        }),
        redirect: "manual",
        signal: AbortSignal.timeout(30_000),
      });
    } catch {
      throw new PlatformPublicationError(503, "站外同步服务暂时无法连接");
    }
    if (!response.ok) {
      let message = "站外同步服务暂时不可用";
      try {
        const payload = await readJsonWithinLimit(response);
        if (payload && typeof payload === "object" && !Array.isArray(payload)) {
          const candidate = (payload as Record<string, unknown>).message;
          if (typeof candidate === "string" && candidate.length <= 200) message = candidate;
        }
      } catch {
        // Keep the external service response generic when it is not a valid JSON error.
      }
      throw new PlatformPublicationError(response.status >= 400 && response.status <= 599 ? response.status : 502, message);
    }

    try {
      const value = await readJsonWithinLimit(response);
      if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("invalid result");
      const data = value as Record<string, unknown>;
      if (data.platform !== input.platform || typeof data.externalId !== "string" || !data.externalId
        || (data.url !== null && data.url !== undefined && typeof data.url !== "string")) {
        throw new Error("invalid result");
      }
      return {
        platform: input.platform,
        externalId: data.externalId,
        url: typeof data.url === "string" ? data.url : null,
      };
    } catch {
      throw new PlatformPublicationError(502, "站外同步服务返回了无效结果");
    }
  }
}
