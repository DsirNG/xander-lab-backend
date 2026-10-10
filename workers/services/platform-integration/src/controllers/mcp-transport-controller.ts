import { McpOAuthError, McpOAuthService } from "../services/mcp-oauth-service.js";
import { McpToolError, PlatformMcpService } from "../services/platform-mcp-service.js";

const MAX_REQUEST_BYTES = 2 * 1024 * 1024 + 64 * 1024;

export async function handleMcpTransportRequest(
  request: Request,
  oauth: McpOAuthService,
  service: PlatformMcpService,
): Promise<Response | null> {
  const url = new URL(request.url);
  const path = url.pathname;
  if (!isMcpPath(path)) return null;
  if (!service.isEnabled(path)) return new Response(null, { status: 404 });

  let principal;
  try { principal = await oauth.authenticateBearer(request.headers.get("authorization")); }
  catch (error) {
    if (error instanceof McpOAuthError) return oauthChallenge(request, oauth, error.error, error.message, error.status);
    return oauthChallenge(request, oauth, "temporarily_unavailable", "MCP OAuth is temporarily unavailable", 503);
  }
  if (!principal) return oauthChallenge(request, oauth, "invalid_token", "A valid bearer token is required", 401);

  if (request.method === "GET") return new Response(null, { status: 405, headers: { allow: "POST" } });
  if (request.method === "DELETE") return new Response(null, { status: 405, headers: { allow: "POST" } });
  if (request.method !== "POST") return new Response(null, { status: 405, headers: { allow: "POST" } });
  const accept = request.headers.get("accept") ?? "*/*";
  if (!accept.includes("*/*") && !accept.includes("application/json") && !accept.includes("text/event-stream")) {
    return new Response(null, { status: 406 });
  }
  if (!request.headers.get("content-type")?.toLowerCase().startsWith("application/json")) {
    return rpcError(null, -32700, "Content-Type must be application/json", 415);
  }

  let rpc: Record<string, unknown>;
  try { rpc = await readRpcBody(request); }
  catch (error) {
    return error instanceof Response ? error : rpcError(null, -32700, "Parse error", 400);
  }
  const hasId = Object.prototype.hasOwnProperty.call(rpc, "id");
  if (rpc.jsonrpc !== "2.0" || typeof rpc.method !== "string" || (hasId && !validId(rpc.id) && rpc.id !== null)) {
    return rpcError(validId(rpc.id) ? rpc.id : null, -32600, "Invalid Request", 400);
  }
  const id = validId(rpc.id) ? rpc.id : null;
  const isNotification = !hasId;
  if (isNotification && rpc.method.startsWith("notifications/")) return new Response(null, { status: 202 });
  if (isNotification) return rpcError(null, -32600, "Requests require an id", 400);

  const params = isObject(rpc.params) ? rpc.params : {};
  if (rpc.method === "initialize") {
    const version = typeof params.protocolVersion === "string" && params.protocolVersion.length <= 100
      ? params.protocolVersion : "2024-11-05";
    return rpcResponse(id, {
      protocolVersion: version,
      capabilities: { tools: { listChanged: false } },
      serverInfo: serverInfo(path),
    });
  }
  if (rpc.method === "ping") return rpcResponse(id, {});
  if (rpc.method === "tools/list") return rpcResponse(id, { tools: service.tools(path) });
  if (rpc.method === "tools/call") {
    if (typeof params.name !== "string" || !params.name || !isObject(params.arguments)) {
      return rpcError(id, -32602, "tools/call requires a name and object arguments", 200);
    }
    try {
      return rpcResponse(id, await service.call(path, principal, id as string | number, params.name, params.arguments));
    } catch (error) {
      const toolError = error instanceof McpToolError
        ? error
        : new McpToolError("TOOL_FAILED", error instanceof Error ? error.message.slice(0, 300) : "Tool call failed");
      return rpcResponse(id, {
        isError: true,
        structuredContent: { ok: false, error: toolError.code, message: toolError.message },
        content: [{ type: "text", text: `${toolError.code}: ${toolError.message}` }],
      });
    }
  }
  return rpcError(id, -32601, "Method not found", 200);
}

async function readRpcBody(request: Request): Promise<Record<string, unknown>> {
  const declared = request.headers.get("content-length");
  if (declared && (!/^\d+$/.test(declared) || Number(declared) > MAX_REQUEST_BYTES)) {
    throw rpcError(null, -32700, "Request body is too large", 413);
  }
  const reader = request.body?.getReader();
  if (!reader) throw rpcError(null, -32700, "Request body is empty", 400);
  const chunks: Uint8Array[] = [];
  let length = 0;
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      length += value.byteLength;
      if (length > MAX_REQUEST_BYTES) {
        await reader.cancel();
        throw rpcError(null, -32700, "Request body is too large", 413);
      }
      chunks.push(value);
    }
  } finally { reader.releaseLock(); }
  const bytes = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  const value: unknown = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
  return isObject(value) ? value : {};
}

function oauthChallenge(request: Request, oauth: McpOAuthService, error: string, description: string, status: number): Response {
  const base = oauth.publicBaseUrl();
  const resource = `${base}${new URL(request.url).pathname}`;
  const metadata = `${base}/.well-known/oauth-protected-resource?resource=${encodeURIComponent(resource)}`;
  const safeDescription = description.replace(/["\r\n]/g, "'").slice(0, 200);
  return json({ error, error_description: safeDescription }, status, {
    "cache-control": "no-store",
    "www-authenticate": `Bearer resource_metadata="${metadata}", error="${error}", error_description="${safeDescription}"`,
  });
}

function rpcResponse(id: string | number | null, result: unknown): Response {
  return json({ jsonrpc: "2.0", id, result }, 200, { "mcp-protocol-version": "2024-11-05" });
}
function rpcError(id: string | number | null, code: number, message: string, status: number): Response {
  return json({ jsonrpc: "2.0", id, error: { code, message } }, status, { "mcp-protocol-version": "2024-11-05" });
}
function json(body: unknown, status: number, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", ...headers },
  });
}
function validId(value: unknown): value is string | number {
  return typeof value === "string" && value.length <= 200 || typeof value === "number" && Number.isSafeInteger(value);
}
function isObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}
function isMcpPath(value: string): boolean {
  return ["/mcp", "/api/mcp", "/api/mcp/csdn", "/api/mcp/dual", "/api/mcp/juejin"].includes(value);
}
function serverInfo(path: string) {
  const name = path === "/api/mcp/csdn" ? "xander-csdn"
    : path === "/api/mcp/juejin" ? "xander-juejin"
      : path === "/api/mcp/dual" ? "xander-dual-publish" : "xander-blog";
  return { name, version: "1.0.0" };
}
