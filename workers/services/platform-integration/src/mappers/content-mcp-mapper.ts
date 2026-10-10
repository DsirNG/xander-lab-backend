import type { PlatformEnvironment } from "../interfaces/platform-environment.js";
import type { PlatformMcpBlogPort, PlatformMcpPublicationPort } from "../interfaces/platform-mcp-ports.js";
import { PlatformMcpPortError } from "../interfaces/platform-mcp-ports.js";

const MAX_RESPONSE_BYTES = 2 * 1024 * 1024 + 64 * 1024;

/** Calls Content's protected owner-scoped MCP article and publication contracts over HTTPS. */
export class ContentMcpMapper implements PlatformMcpBlogPort, PlatformMcpPublicationPort {
  constructor(private readonly env: PlatformEnvironment, private readonly fetcher: typeof fetch = fetch) {}

  list(userId: number, page: number, size: number, search: string): Promise<unknown> {
    return this.blog({ operation: "list", userId, page, size, search });
  }
  get(userId: number, postId: number): Promise<unknown> { return this.blog({ operation: "get", userId, postId }); }
  create(input: {
    userId: number; idempotencyKey: string; title: string; content: string; summary: string | null;
    categoryId: string; tags: string[] | null; publish: boolean;
  }): Promise<unknown> { return this.blog({ operation: "create", ...input }); }
  update(userId: number, postId: number, input: Record<string, unknown>): Promise<unknown> {
    return this.blog({ operation: "update", userId, postId, input });
  }
  async delete(userId: number, postId: number): Promise<void> {
    await this.blog({ operation: "delete", userId, postId });
  }

  async sync(input: {
    userId: number; postId: number; platform: "csdn" | "juejin"; categoryId?: string | null; tagIds?: string[] | null;
  }): Promise<{ platform: "csdn" | "juejin"; externalId: string; url: string | null }> {
    const body = await this.post(
      `/internal/agent/publications/${input.postId}/sync/${input.platform}`,
      {
        userId: input.userId,
        postId: input.postId,
        ...(input.platform === "juejin" ? { juejinCategoryId: input.categoryId, juejinTagIds: input.tagIds } : {}),
      },
      30_000,
    );
    if (!isObject(body) || body.platform !== input.platform || typeof body.externalId !== "string"
      || !body.externalId || (body.url !== null && body.url !== undefined && typeof body.url !== "string")) {
      throw new PlatformMcpPortError(502, "Content returned an invalid publication result");
    }
    return { platform: input.platform, externalId: body.externalId, url: typeof body.url === "string" ? body.url : null };
  }

  private blog(body: Record<string, unknown>): Promise<unknown> {
    return this.post("/internal/platform-integration/mcp/blog", body, 15_000);
  }

  private async post(path: string, body: Record<string, unknown>, timeoutMs: number): Promise<unknown> {
    const base = this.env.CONTENT_AUTOMATION_SERVICE_URL?.trim();
    const token = this.env.CONTENT_AUTOMATION_SERVICE_TOKEN;
    if (!base || !token) throw new PlatformMcpPortError(503, "Content service is not configured");
    let url: URL;
    try { url = new URL(path, base); } catch { throw new PlatformMcpPortError(503, "Content service URL is invalid"); }
    if (url.username || url.password || url.search || url.hash
      || (url.protocol !== "https:" && !(url.protocol === "http:" && ["localhost", "127.0.0.1"].includes(url.hostname)))) {
      throw new PlatformMcpPortError(503, "Content service must use HTTPS");
    }
    let response: Response;
    try {
      response = await this.fetcher(url, {
        method: "POST",
        headers: { "content-type": "application/json", "x-dinqor-service-token": token },
        body: JSON.stringify(body), redirect: "manual", signal: AbortSignal.timeout(timeoutMs),
      });
    } catch { throw new PlatformMcpPortError(503, "Content service is temporarily unavailable"); }
    const payload = await readJsonWithinLimit(response);
    if (!response.ok) {
      const message = isObject(payload) && typeof payload.message === "string" && payload.message.length <= 300
        ? payload.message : "Content service rejected the MCP operation";
      throw new PlatformMcpPortError(response.status, message);
    }
    return payload;
  }
}

export class CoreMcpBillingMapper {
  constructor(private readonly env: PlatformEnvironment, private readonly fetcher: typeof fetch = fetch) {}

  async flatPoints(bizType: "csdn-publish" | "juejin-publish"): Promise<number> {
    const payload = await this.post("/internal/agent/billing/flat-points", { bizType });
    if (!isObject(payload) || !Number.isSafeInteger(payload.points) || (payload.points as number) < 0) {
      throw new PlatformMcpPortError(502, "Core returned an invalid billing price");
    }
    return payload.points as number;
  }

  async charge(input: { userId: number; bizType: string; bizKey: string; bizRef: string; points: number; remark: string }): Promise<void> {
    if (input.points <= 0) return;
    await this.post("/internal/agent/billing/charge-flat", input);
  }

  private async post(path: string, body: Record<string, unknown>): Promise<unknown> {
    const base = this.env.CORE_SERVICE_URL?.trim();
    const token = this.env.CORE_SERVICE_TOKEN;
    if (!base || !token) throw new PlatformMcpPortError(503, "Core billing service is not configured");
    let url: URL;
    try { url = new URL(path, base); } catch { throw new PlatformMcpPortError(503, "Core service URL is invalid"); }
    if (url.username || url.password || url.search || url.hash
      || (url.protocol !== "https:" && !(url.protocol === "http:" && ["localhost", "127.0.0.1"].includes(url.hostname)))) {
      throw new PlatformMcpPortError(503, "Core service must use HTTPS");
    }
    let response: Response;
    try {
      response = await this.fetcher(url, {
        method: "POST", headers: { "content-type": "application/json", "x-dinqor-service-token": token },
        body: JSON.stringify(body), redirect: "manual", signal: AbortSignal.timeout(10_000),
      });
    } catch { throw new PlatformMcpPortError(503, "Core billing service is temporarily unavailable"); }
    const payload = await readJsonWithinLimit(response);
    if (!response.ok || !isObject(payload) || payload.code !== 200) {
      throw new PlatformMcpPortError(response.status || 502, "Core billing operation failed");
    }
    return payload.data;
  }
}

async function readJsonWithinLimit(response: Response): Promise<unknown> {
  const declared = response.headers.get("content-length");
  if (declared && (!/^\d+$/.test(declared) || Number(declared) > MAX_RESPONSE_BYTES)) {
    throw new PlatformMcpPortError(502, "Service response exceeded the allowed size");
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
        throw new PlatformMcpPortError(502, "Service response exceeded the allowed size");
      }
      chunks.push(value);
    }
  } finally { reader.releaseLock(); }
  const bytes = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  try { return JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes)) as unknown; }
  catch { throw new PlatformMcpPortError(502, "Service returned invalid JSON"); }
}

function isObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}
