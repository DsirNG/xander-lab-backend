import { apiError, jsonResponse } from "@dinqor/worker-runtime";
import { AgentMcpError, AgentMcpService } from "../services/agent-mcp-service.js";
import { AgentAuthService } from "../services/agent-auth-service.js";

const PLATFORM_OWNER_ID = 0;
const MAX_BODY_BYTES = 32 * 1024;

function success(data: unknown): Response {
  return jsonResponse({ code: 200, message: "success", data });
}

async function readJson(request: Request): Promise<unknown | Response> {
  const declared = request.headers.get("content-length");
  if (declared && (!/^\d+$/.test(declared) || Number(declared) > MAX_BODY_BYTES)) return apiError(413, "请求内容过大");
  if (!request.body) return apiError(400, "请求内容格式不正确");
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > MAX_BODY_BYTES) { await reader.cancel(); return apiError(413, "请求内容过大"); }
      chunks.push(value);
    }
  } catch { return apiError(400, "请求内容格式不正确"); }
  finally { reader.releaseLock(); }
  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  try { return JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes)) as unknown; }
  catch { return apiError(400, "请求内容格式不正确"); }
}

function positiveId(value: string): number | null {
  if (!/^\d+$/.test(value)) return null;
  const id = Number(value);
  return Number.isSafeInteger(id) && id > 0 ? id : null;
}

/** Owner- and admin-scoped MCP server management endpoints. */
export async function handleAgentMcpRequest(
  request: Request,
  service: AgentMcpService,
  auth: AgentAuthService,
): Promise<Response | null> {
  const path = new URL(request.url).pathname;
  const personal = path === "/api/agent/mcp-servers" || /^\/api\/agent\/mcp-servers\/\d+(?:\/probe)?$/.test(path);
  const admin = path === "/api/admin/mcp-servers" || /^\/api\/admin\/mcp-servers\/\d+(?:\/probe)?$/.test(path);
  if (!personal && !admin) return null;
  const base = admin ? "/api/admin/mcp-servers" : "/api/agent/mcp-servers";
  const collection = path === base;
  const probe = path.endsWith("/probe");
  const idMatch = path.match(/\/(\d+)(?:\/probe)?$/);
  const id = idMatch ? positiveId(idMatch[1]) : null;
  if ((!collection && !id) || (collection && request.method !== "GET" && request.method !== "POST")
    || (!collection && !probe && request.method !== "PUT" && request.method !== "DELETE")
    || (probe && request.method !== "POST")) return null;

  const authenticated = admin ? await auth.currentAdminUserId(request) : await auth.currentUserId(request);
  if (authenticated instanceof Response) return authenticated;
  const ownerUserId = admin ? PLATFORM_OWNER_ID : authenticated;
  try {
    if (request.method === "GET" && collection) return success(await service.list(ownerUserId));
    if (request.method === "POST" && collection) {
      const body = await readJson(request);
      if (body instanceof Response) return body;
      return success(await service.create(ownerUserId, body));
    }
    if (request.method === "POST" && probe) return success(await service.probe(id!, ownerUserId));
    if (request.method === "DELETE") { await service.delete(id!, ownerUserId); return success(null); }
    const body = await readJson(request);
    if (body instanceof Response) return body;
    return success(await service.update(id!, ownerUserId, body));
  } catch (error) {
    if (error instanceof AgentMcpError) return apiError(error.status, error.message);
    console.error("Agent MCP server operation failed", error instanceof Error ? error.message : "unknown error");
    return apiError(503, "智能体服务暂不可用");
  }
}
