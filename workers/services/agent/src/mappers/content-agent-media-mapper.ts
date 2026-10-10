import type { AgentEnvironment } from "../interfaces/agent-environment.js";
import type { AgentMediaAssetPort } from "../interfaces/agent-media-asset-port.js";
import type { AgentImageAsset, AgentImageMediaPort } from "../interfaces/agent-image-media-port.js";

function internalEndpoint(baseUrl: string | undefined, path: string): URL {
  if (!baseUrl) throw new Error("Content media contract is not configured");
  let endpoint: URL;
  try { endpoint = new URL(path, baseUrl); } catch { throw new Error("Content media contract is not configured"); }
  if (endpoint.protocol !== "https:" && endpoint.hostname !== "localhost" && endpoint.hostname !== "127.0.0.1") {
    throw new Error("Content media contract must use HTTPS");
  }
  return endpoint;
}

/** Cross-account HTTPS mapper for Content-owned media assets. */
export class ContentAgentMediaMapper implements AgentMediaAssetPort, AgentImageMediaPort {
  constructor(private readonly env: AgentEnvironment) {}

  async uploadImage(userId: number, originalName: string, contentType: string, bytes: Uint8Array): Promise<{ id: number }> {
    if (!this.env.CONTENT_AUTOMATION_SERVICE_TOKEN) throw new Error("Content media contract is not configured");
    const body = new ArrayBuffer(bytes.byteLength);
    new Uint8Array(body).set(bytes);
    let response: Response;
    try {
      response = await fetch(internalEndpoint(this.env.CONTENT_AUTOMATION_SERVICE_URL,
        "/internal/content/media/upload-image"), {
        method: "POST",
        headers: {
          "content-type": "application/octet-stream",
          "x-dinqor-service-token": this.env.CONTENT_AUTOMATION_SERVICE_TOKEN,
          "x-dinqor-user-id": String(userId),
          "x-dinqor-content-type": contentType,
          "x-dinqor-original-name": encodeURIComponent(originalName),
        },
        body,
        redirect: "manual",
        signal: AbortSignal.timeout(30_000),
      });
    } catch { throw new Error("Content media contract failed"); }
    if (!response.ok) throw new Error("Content media upload failed");
    const result: unknown = await response.json();
    if (!result || typeof result !== "object" || Array.isArray(result)) throw new Error("Content media upload returned invalid data");
    const id = (result as Record<string, unknown>).id;
    if (typeof id !== "number" || !Number.isSafeInteger(id) || id < 1) throw new Error("Content media upload returned invalid data");
    return { id };
  }

  async deleteAsset(userId: number, id: number): Promise<void> {
    if (!this.env.CONTENT_AUTOMATION_SERVICE_TOKEN) throw new Error("Content media contract is not configured");
    let response: Response;
    try {
      response = await fetch(internalEndpoint(this.env.CONTENT_AUTOMATION_SERVICE_URL,
        "/internal/content/media/delete-owned"), {
        method: "POST",
        headers: { "content-type": "application/json", "x-dinqor-service-token": this.env.CONTENT_AUTOMATION_SERVICE_TOKEN },
        body: JSON.stringify({ userId, id }),
        redirect: "manual",
        signal: AbortSignal.timeout(10_000),
      });
    } catch { throw new Error("Content media cleanup failed"); }
    if (!response.ok) throw new Error("Content media cleanup failed");
  }

  async recordGenerated(
    userId: number,
    originalName: string,
    contentType: string,
    bytes: Uint8Array,
    generationMeta: string,
  ): Promise<AgentImageAsset> {
    if (!this.env.CONTENT_AUTOMATION_SERVICE_TOKEN) throw new Error("Content media contract is not configured");
    const body = new ArrayBuffer(bytes.byteLength);
    new Uint8Array(body).set(bytes);
    let response: Response;
    try {
      response = await fetch(internalEndpoint(this.env.CONTENT_AUTOMATION_SERVICE_URL,
        "/internal/content/media/record-generated"), {
        method: "POST",
        headers: {
          "content-type": "application/octet-stream",
          "x-dinqor-service-token": this.env.CONTENT_AUTOMATION_SERVICE_TOKEN,
          "x-dinqor-user-id": String(userId),
          "x-dinqor-content-type": contentType,
          "x-dinqor-original-name": encodeURIComponent(originalName),
          "x-dinqor-generation-meta": encodeURIComponent(generationMeta),
        },
        body,
        redirect: "manual",
        signal: AbortSignal.timeout(30_000),
      });
    } catch { throw new Error("Content media contract failed"); }
    if (!response.ok) throw new Error("Content media registration failed");
    const value: unknown = await response.json();
    if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("Content media registration returned invalid data");
    const result = value as Record<string, unknown>;
    if (typeof result.id !== "number" || !Number.isSafeInteger(result.id) || result.id < 1
      || typeof result.url !== "string" || typeof result.contentType !== "string") {
      throw new Error("Content media registration returned invalid data");
    }
    return {
      id: result.id, url: result.url,
      width: typeof result.width === "number" ? result.width : null,
      height: typeof result.height === "number" ? result.height : null,
      contentType: result.contentType,
    };
  }

  async findOwned(userId: number, id: number): Promise<AgentImageAsset | null> {
    if (!this.env.CONTENT_AUTOMATION_SERVICE_TOKEN) throw new Error("Content media contract is not configured");
    let response: Response;
    try {
      response = await fetch(internalEndpoint(this.env.CONTENT_AUTOMATION_SERVICE_URL,
        "/internal/content/media/find-owned"), {
        method: "POST",
        headers: { "content-type": "application/json", "x-dinqor-service-token": this.env.CONTENT_AUTOMATION_SERVICE_TOKEN },
        body: JSON.stringify({ userId, id }),
        redirect: "manual",
        signal: AbortSignal.timeout(10_000),
      });
    } catch { throw new Error("Content media lookup failed"); }
    if (!response.ok) throw new Error("Content media lookup failed");
    const value: unknown = await response.json();
    if (value === null) return null;
    if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("Content media lookup returned invalid data");
    const result = value as Record<string, unknown>;
    if (typeof result.id !== "number" || !Number.isSafeInteger(result.id) || result.id !== id
      || typeof result.url !== "string" || typeof result.contentType !== "string") {
      throw new Error("Content media lookup returned invalid data");
    }
    return {
      id, url: result.url, contentType: result.contentType,
      width: typeof result.width === "number" ? result.width : null,
      height: typeof result.height === "number" ? result.height : null,
      originalName: typeof result.originalName === "string" ? result.originalName : "",
      size: typeof result.size === "number" ? result.size : undefined,
    };
  }
}
