import { jsonResponse } from "@dinqor/worker-runtime";
import type { PlatformEnvironment } from "../interfaces/platform-environment.js";
import { McpOAuthError, McpOAuthService } from "../services/mcp-oauth-service.js";

const MAX_REQUEST_BYTES = 16 * 1024;

export async function handleMcpOAuthRequest(
  request: Request,
  env: PlatformEnvironment,
  service: McpOAuthService,
): Promise<Response | null> {
  const url = new URL(request.url);
  const path = url.pathname;
  try {
    if (request.method === "GET" && path === "/.well-known/oauth-authorization-server") {
      const base = service.publicBaseUrl();
      return json({
        issuer: base,
        authorization_endpoint: `${base}/api/mcp/oauth/authorize`,
        token_endpoint: `${base}/api/mcp/oauth/token`,
        registration_endpoint: `${base}/api/mcp/oauth/register`,
        revocation_endpoint: `${base}/api/mcp/oauth/revoke`,
        response_types_supported: ["code"], grant_types_supported: ["authorization_code", "refresh_token"],
        code_challenge_methods_supported: ["S256"], token_endpoint_auth_methods_supported: ["none"],
        scopes_supported: McpOAuthService.supportedScopes(),
      });
    }
    if (request.method === "GET" && path === "/.well-known/oauth-protected-resource") {
      const base = service.publicBaseUrl();
      const resource = url.searchParams.get("resource")?.trim() || `${base}/mcp`;
      const allowed = ["/mcp", "/api/mcp", "/api/mcp/csdn", "/api/mcp/dual", "/api/mcp/juejin"]
        .map((suffix) => `${base}${suffix}`);
      if (!allowed.includes(resource)) throw new McpOAuthError("invalid_request", 400, "resource is not a DinQorAI MCP endpoint");
      return json({
        resource, authorization_servers: [base], scopes_supported: McpOAuthService.scopesForResource(resource),
        bearer_methods_supported: ["header"],
      });
    }

    if (path === "/api/mcp/oauth/register" && request.method === "POST") {
      await service.checkRateLimit("register", clientIp(request));
      const body = await readJson(request);
      const client = await service.registerClient(body.client_name, body.redirect_uris);
      return json({
        client_id: client.clientId, client_name: client.clientName, redirect_uris: client.redirectUris,
        token_endpoint_auth_method: "none",
      }, 201);
    }

    if (path === "/api/mcp/oauth/authorize" && request.method === "GET") {
      await service.checkRateLimit("authorize", clientIp(request));
      const authorization = await service.startAuthorization({
        responseType: url.searchParams.get("response_type"), clientId: url.searchParams.get("client_id"),
        redirectUri: url.searchParams.get("redirect_uri"), scope: url.searchParams.get("scope"),
        state: url.searchParams.get("state"), codeChallenge: url.searchParams.get("code_challenge"),
        codeChallengeMethod: url.searchParams.get("code_challenge_method"), resource: url.searchParams.get("resource"),
      });
      return new Response(null, { status: 302, headers: { location: authorization.consentUrl, "cache-control": "no-store" } });
    }

    const pending = path.match(/^\/api\/mcp\/oauth\/authorize\/requests\/([A-Za-z0-9_-]{1,128})$/);
    if (pending && request.method === "GET") {
      await service.checkRateLimit("consent", clientIp(request));
      return json(await service.pendingAuthorization(pending[1], ownerId(request)));
    }

    const approve = path.match(/^\/api\/mcp\/oauth\/authorize\/requests\/([A-Za-z0-9_-]{1,128})\/approve$/);
    if (approve && request.method === "POST") {
      await service.checkRateLimit("approve", clientIp(request));
      const scopes = url.searchParams.getAll("scopes").flatMap((value) => value.split(/\s+/).filter(Boolean));
      const redirectUrl = await service.approveAuthorization(approve[1], ownerId(request), scopes.length ? scopes : undefined);
      return json({ redirectUrl });
    }

    const deny = path.match(/^\/api\/mcp\/oauth\/authorize\/requests\/([A-Za-z0-9_-]{1,128})\/deny$/);
    if (deny && request.method === "POST") {
      await service.checkRateLimit("deny", clientIp(request));
      return json({ redirectUrl: await service.denyAuthorization(deny[1], ownerId(request)) });
    }

    if (path === "/api/mcp/oauth/token" && request.method === "POST") {
      await service.checkRateLimit("token", clientIp(request));
      const form = await readForm(request);
      const token = await service.issueToken({
        grantType: form.get("grant_type"), clientId: form.get("client_id"), code: form.get("code"),
        redirectUri: form.get("redirect_uri"), codeVerifier: form.get("code_verifier"),
        refreshToken: form.get("refresh_token"),
      });
      return json({
        access_token: token.accessToken, token_type: "Bearer", expires_in: token.expiresIn,
        refresh_token: token.refreshToken, scope: token.scope,
      }, 200, true);
    }

    if (path === "/api/mcp/oauth/revoke" && request.method === "POST") {
      await service.checkRateLimit("revoke", clientIp(request));
      const form = await readForm(request);
      await service.revokeToken(form.get("token"), form.get("client_id"));
      return new Response(null, { status: 200, headers: { "cache-control": "no-store" } });
    }

    if (path === "/api/mcp/oauth/clients" && request.method === "GET") {
      return json(await service.listClientGrants(ownerId(request)));
    }
    const clientRevocation = path.match(/^\/api\/mcp\/oauth\/clients\/([^/]{1,128})$/);
    if (clientRevocation && request.method === "DELETE") {
      await service.revokeClientGrant(decodeURIComponent(clientRevocation[1]), ownerId(request));
      return new Response(null, { status: 204 });
    }
    return null;
  } catch (error) {
    if (error instanceof McpOAuthError) return json({ error: error.error, error_description: error.message }, error.status, true);
    return json({ error: "temporarily_unavailable", error_description: "MCP OAuth is temporarily unavailable" }, 503, true);
  }
}

async function readJson(request: Request): Promise<Record<string, unknown>> {
  const raw = await readBody(request, "application/json");
  try {
    const parsed: unknown = JSON.parse(raw);
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) throw new Error("object required");
    return parsed as Record<string, unknown>;
  } catch { throw new McpOAuthError("invalid_request", 400, "request body must be a JSON object"); }
}

async function readForm(request: Request): Promise<URLSearchParams> {
  if (!request.headers.get("content-type")?.toLowerCase().startsWith("application/x-www-form-urlencoded")) {
    throw new McpOAuthError("invalid_request", 400, "form-urlencoded request body is required");
  }
  return new URLSearchParams(await readBody(request, "application/x-www-form-urlencoded"));
}

async function readBody(request: Request, mediaType: string): Promise<string> {
  const contentType = request.headers.get("content-type")?.toLowerCase() ?? "";
  if (!contentType.startsWith(mediaType)) throw new McpOAuthError("invalid_request", 400, `${mediaType} request body is required`);
  const declared = request.headers.get("content-length");
  if (declared && (!/^\d+$/.test(declared) || Number(declared) > MAX_REQUEST_BYTES)) {
    throw new McpOAuthError("invalid_request", 413, "request body is too large");
  }
  const reader = request.body?.getReader();
  if (!reader) return "";
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > MAX_REQUEST_BYTES) {
        await reader.cancel();
        throw new McpOAuthError("invalid_request", 413, "request body is too large");
      }
      chunks.push(value);
    }
  } finally { reader.releaseLock(); }
  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  return new TextDecoder("utf-8", { fatal: true }).decode(bytes);
}

function ownerId(request: Request): number {
  const value = request.headers.get("x-user-id");
  if (!value || !/^[1-9]\d{0,17}$/.test(value)) throw new McpOAuthError("login_required", 401, "DinQorAI login is required");
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed)) throw new McpOAuthError("login_required", 401, "DinQorAI login is required");
  return parsed;
}

function clientIp(request: Request): string {
  const cf = request.headers.get("cf-connecting-ip")?.trim();
  if (cf) return cf;
  const forwarded = request.headers.get("x-forwarded-for")?.split(",")[0]?.trim();
  if (forwarded) return forwarded;
  return request.headers.get("x-real-ip")?.trim() || "unknown";
}

function json(body: unknown, status = 200, noStore = false): Response {
  const response = jsonResponse(body, status);
  const headers = new Headers(response.headers);
  if (noStore) headers.set("cache-control", "no-store");
  return new Response(response.body, { status, headers });
}
