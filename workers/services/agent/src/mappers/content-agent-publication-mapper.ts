import type { AgentPublicationPort, AgentPublicationResult } from "../interfaces/agent-publication-port.js";
import { fetchWithServiceBinding, type WorkerServiceBinding } from "@dinqor/worker-runtime";

export interface ContentAgentPublicationEnvironment {
  CONTENT_AUTOMATION_SERVICE_URL?: string;
  CONTENT_AUTOMATION_SERVICE_TOKEN?: string;
  CONTENT_AUTOMATION_SERVICE?: WorkerServiceBinding;
}

const MAX_RESPONSE_BYTES = 16 * 1024;

async function readJson(response: Response): Promise<unknown> {
  const length = response.headers.get("content-length");
  if (length && Number(length) > MAX_RESPONSE_BYTES) throw new Error("Content response too large");
  if (!response.body) return null;
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > MAX_RESPONSE_BYTES) {
        await reader.cancel();
        throw new Error("Content response too large");
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes)) as unknown;
}

function publicationResult(value: unknown, platform: "csdn" | "juejin"): AgentPublicationResult | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const row = value as Record<string, unknown>;
  if (row.platform !== platform || typeof row.externalId !== "string" || !row.externalId
    || (row.url !== null && row.url !== undefined && typeof row.url !== "string")) return null;
  return { platform, externalId: row.externalId, url: typeof row.url === "string" ? row.url : null };
}

/** Calls Content through its service-token contract; the user ID is always explicit and ownership-scoped. */
export class ContentAgentPublicationMapper implements AgentPublicationPort {
  constructor(private readonly env: ContentAgentPublicationEnvironment) {}

  findSucceeded(userId: number, postId: number, platform: "csdn" | "juejin"): Promise<AgentPublicationResult | null> {
    return this.call(userId, postId, platform, "find-succeeded");
  }

  async sync(userId: number, postId: number, platform: "csdn" | "juejin"): Promise<AgentPublicationResult> {
    const result = await this.call(userId, postId, platform, "sync");
    if (!result) throw new Error("Content returned an invalid publication result");
    return result;
  }

  private async call(
    userId: number,
    postId: number,
    platform: "csdn" | "juejin",
    action: "sync" | "find-succeeded",
  ): Promise<AgentPublicationResult | null> {
    if (!Number.isSafeInteger(userId) || userId < 1 || !Number.isSafeInteger(postId) || postId < 1) {
      throw new Error("Invalid Content publication identity");
    }
    const baseUrl = this.env.CONTENT_AUTOMATION_SERVICE_URL?.trim();
    const token = this.env.CONTENT_AUTOMATION_SERVICE_TOKEN;
    if (!baseUrl || !token) throw new Error("Content publication service is not configured");

    let endpoint: URL;
    try {
      endpoint = new URL(`/internal/agent/publications/${postId}/${action}/${platform}`, baseUrl);
    } catch {
      throw new Error("Content publication service configuration is invalid");
    }
    if (endpoint.username || endpoint.password || endpoint.search || endpoint.hash
      || (endpoint.protocol !== "https:"
        && !(endpoint.protocol === "http:" && ["127.0.0.1", "localhost"].includes(endpoint.hostname)))) {
      throw new Error("Content publication service must use HTTPS");
    }

    let response: Response;
    try {
      response = await fetchWithServiceBinding(this.env.CONTENT_AUTOMATION_SERVICE, endpoint, {
        method: "POST",
        headers: { "content-type": "application/json", "x-dinqor-service-token": token },
        body: JSON.stringify({ userId, postId }),
        redirect: "manual",
        signal: AbortSignal.timeout(30_000),
      });
    } catch {
      throw new AgentPublicationError(503, "Content publication request could not be confirmed");
    }
    let value: unknown;
    try {
      value = await readJson(response);
    } catch {
      if (!response.ok) throw new AgentPublicationError(response.status, "Content publication failed");
      throw new AgentPublicationError(502, "Content returned an invalid publication result");
    }
    if (!response.ok) {
      let message = "Content publication failed";
      if (value && typeof value === "object" && !Array.isArray(value)) {
        const candidate = (value as Record<string, unknown>).message;
        if (typeof candidate === "string" && candidate.length <= 200) message = candidate;
      }
      throw new AgentPublicationError(response.status, message);
    }
    if (action === "find-succeeded" && value === null) return null;
    const result = publicationResult(value, platform);
    if (!result) throw new AgentPublicationError(502, "Content returned an invalid publication result");
    return result;
  }
}

export class AgentPublicationError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
    this.name = "AgentPublicationError";
  }
}
