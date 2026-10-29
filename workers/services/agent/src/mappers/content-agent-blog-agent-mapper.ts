import type { AgentBlogAgentPort, AgentBlogTaskSummary } from "../interfaces/agent-blog-agent-port.js";
import { fetchWithServiceBinding } from "@dinqor/worker-runtime";
import type { ContentAgentPublicationEnvironment } from "./content-agent-publication-mapper.js";

const MAX_JSON_BYTES = 64 * 1024;
const MAX_STREAM_BYTES = 4 * 1024 * 1024;

async function readLimited(response: Response, max: number): Promise<string> {
  const declared = response.headers.get("content-length");
  if (declared && Number(declared) > max) throw new Error("Content blog response is too large");
  const reader = response.body?.getReader();
  if (!reader) return "";
  const chunks: Uint8Array[] = [];
  let total = 0;
  const decoder = new TextDecoder();
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > max) { await reader.cancel(); throw new Error("Content blog response is too large"); }
      chunks.push(value);
    }
  } finally { reader.releaseLock(); }
  return chunks.map((chunk) => decoder.decode(chunk, { stream: true })).join("") + decoder.decode();
}

async function readSse(response: Response, max: number, consume: (block: string) => Promise<void>): Promise<void> {
  const declared = response.headers.get("content-length");
  if (declared && Number(declared) > max) throw new Error("Content blog response is too large");
  const reader = response.body?.getReader();
  if (!reader) return;
  const decoder = new TextDecoder();
  let buffer = "";
  let total = 0;
  let finished = false;
  const drain = async (final = false) => {
    let boundary: RegExpExecArray | null;
    while ((boundary = /\r?\n\r?\n/.exec(buffer))) {
      const block = buffer.slice(0, boundary.index);
      buffer = buffer.slice(boundary.index + boundary[0].length);
      await consume(block);
    }
    if (final && buffer) {
      const block = buffer;
      buffer = "";
      await consume(block);
    }
  };
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) { finished = true; break; }
      total += value.byteLength;
      if (total > max) throw new Error("Content blog response is too large");
      buffer += decoder.decode(value, { stream: true });
      await drain();
    }
    buffer += decoder.decode();
    await drain(true);
  } finally {
    if (!finished) await reader.cancel().catch(() => undefined);
    reader.releaseLock();
  }
}

function summary(value: unknown): AgentBlogTaskSummary | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const row = value as Record<string, unknown>;
  if (!Number.isSafeInteger(row.id) || (row.id as number) < 1) return null;
  return {
    id: row.id as number,
    title: typeof row.title === "string" ? row.title : "",
    summary: typeof row.summary === "string" ? row.summary : "",
    publishedPostId: Number.isSafeInteger(row.publishedPostId) ? row.publishedPostId as number : null,
    status: typeof row.status === "string" ? row.status : "",
    generationMode: row.generationMode === "quick" ? "quick" : "full",
  };
}

function taskView(value: unknown): AgentBlogTaskSummary | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  return summary((value as Record<string, unknown>).task);
}

export class AgentBlogAgentError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
    this.name = "AgentBlogAgentError";
  }
}

/** The Agent talks to Content through a versioned HTTPS service-authenticated task contract. */
export class ContentAgentBlogAgentMapper implements AgentBlogAgentPort {
  constructor(private readonly env: ContentAgentPublicationEnvironment) {}

  async create(userId: number, input: string, clientRequestId: string, mode: "auto" | "quick" | "full" = "auto"): Promise<AgentBlogTaskSummary> {
    const value = await this.post("/internal/agent/blogagent/create", { userId, input, clientRequestId, mode });
    const task = summary(value);
    if (!task) throw new AgentBlogAgentError(502, "Content returned an invalid BlogAgent task");
    return task;
  }

  async findReady(userId: number, clientRequestId: string): Promise<AgentBlogTaskSummary | null> {
    const value = await this.post("/internal/agent/blogagent/find-ready", { userId, clientRequestId });
    if (value === null) return null;
    const task = summary(value);
    if (!task) throw new AgentBlogAgentError(502, "Content returned an invalid BlogAgent task");
    return task;
  }

  async runStream(userId: number, taskId: number, signal: AbortSignal,
    onStage: (stage: string, message: string) => Promise<void>): Promise<AgentBlogTaskSummary> {
    const { endpoint, token } = this.configuration(`/internal/agent/blogagent/${taskId}/run-stream`);
    let response: Response;
    try {
      response = await fetchWithServiceBinding(this.env.CONTENT_AUTOMATION_SERVICE, endpoint, {
        method: "POST", headers: { "content-type": "application/json", accept: "text/event-stream",
          "x-dinqor-service-token": token },
        body: JSON.stringify({ userId }), redirect: "manual", signal,
      });
    } catch (error) {
      if (signal.aborted) throw error;
      throw new AgentBlogAgentError(503, "Content BlogAgent stream is unavailable");
    }
    if (!response.ok) {
      const body = await readLimited(response, MAX_JSON_BYTES).catch(() => "");
      let message = `Content BlogAgent returned HTTP ${response.status}`;
      try {
        const parsed = JSON.parse(body) as Record<string, unknown>;
        if (typeof parsed.message === "string" && parsed.message.length <= 300) message = parsed.message;
      } catch { /* Keep the bounded status message. */ }
      throw new AgentBlogAgentError(response.status, message);
    }
    if (!response.headers.get("content-type")?.toLowerCase().includes("text/event-stream")) {
      throw new AgentBlogAgentError(502, "Content returned an invalid BlogAgent stream");
    }
    const completion: { task: AgentBlogTaskSummary | null } = { task: null };
    await readSse(response, MAX_STREAM_BYTES, async (block) => {
      if (!block.trim() || block.trim().startsWith(":")) return;
      const type = block.split(/\r?\n/).find((line) => line.startsWith("event:"))?.slice(6).trim();
      const data = block.split(/\r?\n/).filter((line) => line.startsWith("data:"))
        .map((line) => line.slice(5).replace(/^ /, "")).join("\n");
      if (type === "stage") {
        const separator = data.indexOf("|");
        await onStage(separator < 0 ? "" : data.slice(0, separator), separator < 0 ? data : data.slice(separator + 1));
      } else if (type === "error") {
        throw new AgentBlogAgentError(502, data.slice(0, 1_000) || "博客生成助手执行失败");
      } else if (type === "complete") {
        let parsed: unknown;
        try { parsed = JSON.parse(data); }
        catch { throw new AgentBlogAgentError(502, "Content returned an invalid BlogAgent result"); }
        completion.task = taskView(parsed);
      }
    });
    if (!completion.task || completion.task.status !== "ready") {
      throw new AgentBlogAgentError(502, "Content BlogAgent stream ended before completion");
    }
    return completion.task;
  }

  private async post(path: string, body: Record<string, unknown>): Promise<unknown> {
    const { endpoint, token } = this.configuration(path);
    let response: Response;
    try {
      response = await fetchWithServiceBinding(this.env.CONTENT_AUTOMATION_SERVICE, endpoint, { method: "POST", headers: { "content-type": "application/json",
        "x-dinqor-service-token": token }, body: JSON.stringify(body), redirect: "manual",
        signal: AbortSignal.timeout(30_000) });
    } catch { throw new AgentBlogAgentError(503, "Content BlogAgent service is unavailable"); }
    const text = await readLimited(response, MAX_JSON_BYTES).catch(() => "");
    let payload: unknown;
    try { payload = text ? JSON.parse(text) as unknown : null; }
    catch { throw new AgentBlogAgentError(502, "Content returned an invalid BlogAgent response"); }
    if (!response.ok) {
      const message = payload && typeof payload === "object" && typeof (payload as Record<string, unknown>).message === "string"
        ? (payload as Record<string, unknown>).message as string : "Content BlogAgent request failed";
      throw new AgentBlogAgentError(response.status, message.slice(0, 300));
    }
    return payload;
  }

  private configuration(path: string): { endpoint: URL; token: string } {
    const base = this.env.CONTENT_AUTOMATION_SERVICE_URL?.trim();
    const token = this.env.CONTENT_AUTOMATION_SERVICE_TOKEN;
    if (!base || !token) throw new AgentBlogAgentError(503, "Content BlogAgent service is not configured");
    let endpoint: URL;
    try { endpoint = new URL(path, base); }
    catch { throw new AgentBlogAgentError(503, "Content BlogAgent service URL is invalid"); }
    if (endpoint.username || endpoint.password || endpoint.search || endpoint.hash
      || (endpoint.protocol !== "https:" && !(endpoint.protocol === "http:" && ["127.0.0.1", "localhost"].includes(endpoint.hostname)))) {
      throw new AgentBlogAgentError(503, "Content BlogAgent service must use HTTPS");
    }
    return { endpoint, token };
  }
}
