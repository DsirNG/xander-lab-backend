import {
  apiError,
  healthResponse,
  readyResponse,
  verifyAccessToken,
  type AccessTokenClaims,
} from "@dinqor/worker-runtime";
import { ACCESS_POLICY } from "./access-policy.generated.js";
import { ROUTES } from "./routes.generated.js";

type ServiceName =
  | "core"
  | "agent"
  | "platform-integration"
  | "content-automation"
  | "bloghtml-preview"
  | "payment";

interface GatewayEnvironment {
  JWT_SECRET?: string;
  CORS_ALLOWED_ORIGINS?: string;
  GATEWAY_RATE_LIMIT_ENABLED?: string;
  GATEWAY_RATE_LIMITER?: {
    limit(input: { key: string }): Promise<{ success: boolean }>;
  };
  CORE_SERVICE_URL?: string;
  CORE_SERVICE_TOKEN?: string;
  AGENT_SERVICE_URL?: string;
  AGENT_SERVICE_TOKEN?: string;
  PLATFORM_INTEGRATION_SERVICE_URL?: string;
  PLATFORM_INTEGRATION_SERVICE_TOKEN?: string;
  CONTENT_AUTOMATION_SERVICE_URL?: string;
  CONTENT_AUTOMATION_SERVICE_TOKEN?: string;
  BLOGHTML_PREVIEW_SERVICE_URL?: string;
  BLOGHTML_PREVIEW_SERVICE_TOKEN?: string;
  PAYMENT_SERVICE_URL?: string;
  PAYMENT_SERVICE_TOKEN?: string;
}

interface Route {
  method: string;
  path: string;
  service: ServiceName;
  matcher: RegExp;
}

interface PolicyMatch {
  method?: string | string[];
  path?: string;
  path_pattern?: string;
  path_prefix?: string;
  path_prefix_also?: string;
  path_contains?: string;
}

interface PolicyRule {
  group: string;
  match: PolicyMatch;
  access?: "public" | "excluded" | "authenticated" | "admin";
  identity_mode?: "none" | "optional" | "required";
  token_location?: string;
}

interface AccessDecision {
  access: "public" | "excluded" | "authenticated" | "admin";
  identityMode: "none" | "optional" | "required";
  tokenLocation: string;
}

interface UserIdentity {
  userId: string;
  role?: string;
  jti?: string;
  sid?: string;
}

interface CorsContext {
  origin: string | null;
  allowed: boolean;
  preflight: boolean;
}

const POLICY_GROUPS = [
  "preflight",
  "excluded",
  "prefix_rules",
  "explicit_private_get",
  "explicit_public_write",
  "public_get",
  "public_non_api",
  "streams",
  "protected",
] as const;

const POLICY_ROOT = ACCESS_POLICY as unknown as Record<string, unknown>;
const policyRules = loadPolicyRules();
const compiledRoutes: Route[] = ROUTES.map((route) => ({
  ...route,
  matcher: patternToRegExp(route.path),
}));
const SAFE_CORRELATION_ID = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/;
const CORS_METHODS = ["GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"];

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function loadPolicyRules(): PolicyRule[] {
  const rules: PolicyRule[] = [];
  for (const group of POLICY_GROUPS) {
    const configured = POLICY_ROOT[group];
    const candidates = Array.isArray(configured) ? configured : [configured];
    for (const candidate of candidates) {
      if (!isRecord(candidate) || !isRecord(candidate.match)) continue;
      rules.push({
        group,
        match: candidate.match as PolicyMatch,
        access: candidate.access as PolicyRule["access"],
        identity_mode: candidate.identity_mode as PolicyRule["identity_mode"],
        token_location: typeof candidate.token_location === "string"
          ? candidate.token_location
          : undefined,
      });
    }
  }
  return rules;
}

function patternToRegExp(pattern: string): RegExp {
  let parameterIndex = 0;
  const parameters: string[] = [];
  let source = pattern.replace(/\{[^/{}]+\}/g, () => {
    const token = `__D1_PARAM_${parameterIndex++}__`;
    parameters.push(token);
    return token;
  });
  source = source.replace(/[.+?^${}()|[\]\\]/g, "\\$&");
  source = source.replace(/\*\*/g, ".*").replace(/\*/g, "[^/]+");
  for (const token of parameters) source = source.replace(token, "[^/]+");
  return new RegExp(`^${source}$`);
}

function globMatches(pattern: string, path: string): boolean {
  let source = "^";
  for (let index = 0; index < pattern.length; index += 1) {
    const character = pattern[index];
    if (character === "*" && pattern[index + 1] === "*") {
      source += ".*";
      index += 1;
    } else if (character === "*") {
      source += "[^/]+";
    } else {
      source += character.replace(/[.+?^${}()|[\]\\]/g, "\\$&");
    }
  }
  return new RegExp(`${source}$`).test(path);
}

function methodMatches(expected: string | string[] | undefined, actual: string): boolean {
  if (expected === undefined) return true;
  const candidates = Array.isArray(expected) ? expected : [expected];
  return candidates.some((method) => method === "ANY" || method.toUpperCase() === actual);
}

function ruleMatches(rule: PolicyRule, method: string, path: string): boolean {
  if (!methodMatches(rule.match.method, method)) return false;
  if (rule.group === "preflight" && rule.match.path === "/**") return true;

  if (rule.match.path === path) return true;
  if (rule.match.path_pattern && globMatches(rule.match.path_pattern, path)) return true;
  if (rule.match.path_prefix && path.startsWith(rule.match.path_prefix)) return true;
  if (rule.match.path_prefix_also) {
    const prefix = rule.match.path_prefix_also;
    if (path === prefix || path.startsWith(prefix.endsWith("/") ? prefix : `${prefix}/`)) {
      return true;
    }
  }
  return Boolean(rule.match.path_contains && path.includes(rule.match.path_contains));
}

function accessDecision(method: string, path: string): AccessDecision {
  const match = policyRules.find((rule) => ruleMatches(rule, method, path));
  if (match) {
    const access = match.access
      ?? (match.group === "public_get" || match.group === "public_non_api" ? "public" : "authenticated");
    return {
      access,
      identityMode: match.identity_mode
        ?? (access === "authenticated" || access === "admin" ? "required" : "optional"),
      tokenLocation: match.token_location ?? "header",
    };
  }

  const defaults = POLICY_ROOT.default;
  const readRule = isRecord(defaults) && isRecord(defaults.read) ? defaults.read : {};
  const writeRule = isRecord(defaults) && isRecord(defaults.write) ? defaults.write : {};
  const readMethods = Array.isArray(readRule.methods) ? readRule.methods : ["GET", "HEAD", "OPTIONS"];
  const fallback = readMethods.some((candidate) =>
    typeof candidate === "string" && (candidate === "ANY" || candidate.toUpperCase() === method))
    ? readRule
    : writeRule;
  const access = fallback.access === "authenticated" || fallback.access === "admin"
    ? fallback.access
    : "public";
  const identityMode = fallback.identity_mode === "none" || fallback.identity_mode === "optional"
    ? fallback.identity_mode
    : "required";
  return {
    access,
    identityMode,
    tokenLocation: typeof fallback.token_location === "string" ? fallback.token_location : "header",
  };
}

function destination(service: ServiceName, env: GatewayEnvironment) {
  switch (service) {
    case "core":
      return [env.CORE_SERVICE_URL, env.CORE_SERVICE_TOKEN] as const;
    case "agent":
      return [env.AGENT_SERVICE_URL, env.AGENT_SERVICE_TOKEN] as const;
    case "platform-integration":
      return [env.PLATFORM_INTEGRATION_SERVICE_URL, env.PLATFORM_INTEGRATION_SERVICE_TOKEN] as const;
    case "content-automation":
      return [env.CONTENT_AUTOMATION_SERVICE_URL, env.CONTENT_AUTOMATION_SERVICE_TOKEN] as const;
    case "bloghtml-preview":
      return [env.BLOGHTML_PREVIEW_SERVICE_URL, env.BLOGHTML_PREVIEW_SERVICE_TOKEN] as const;
    case "payment":
      return [env.PAYMENT_SERVICE_URL, env.PAYMENT_SERVICE_TOKEN] as const;
  }
}

function serviceBaseUrl(value: string | undefined): URL | null {
  if (!value) return null;
  try {
    const url = new URL(value);
    const isLocalHttp = url.protocol === "http:"
      && (url.hostname === "localhost" || url.hostname === "127.0.0.1");
    if ((url.protocol !== "https:" && !isLocalHttp)
      || url.username || url.password || url.search || url.hash || url.pathname !== "/") {
      return null;
    }
    return url;
  } catch {
    return null;
  }
}

function bearerToken(request: Request, location: string): string | null {
  const authorization = request.headers.get("authorization");
  if (authorization?.startsWith("Bearer ")) {
    const token = authorization.slice(7).trim();
    if (token) return token;
  }
  if (location === "query" || location === "header_or_query") {
    const token = new URL(request.url).searchParams.get("token")?.trim();
    return token || null;
  }
  return null;
}

function trustedIdentity(claims: AccessTokenClaims): UserIdentity | null {
  if (!/^[1-9]\d{0,17}$/.test(claims.sub) || !Number.isSafeInteger(Number(claims.sub))) {
    return null;
  }
  const role = typeof claims.role === "string" && claims.role.trim() ? claims.role : undefined;
  const jti = typeof claims.jti === "string" && claims.jti.trim() ? claims.jti : undefined;
  const sid = typeof claims.sid === "string" && claims.sid.trim() ? claims.sid : undefined;
  return { userId: claims.sub, role, jti, sid };
}

type AuthResolution = "valid" | "invalid" | "unavailable";

async function resolveActiveIdentity(
  token: string,
  identity: UserIdentity,
  env: GatewayEnvironment,
): Promise<AuthResolution> {
  const [coreUrl, coreToken] = destination("core", env);
  const baseUrl = serviceBaseUrl(coreUrl);
  if (!baseUrl || !coreToken) return "unavailable";

  try {
    const endpoint = new URL("/internal/agent/auth/resolve", baseUrl);
    const response = await fetch(endpoint, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-dinqor-service-token": coreToken,
      },
      body: JSON.stringify({ accessToken: token }),
      redirect: "manual",
      signal: AbortSignal.timeout(5_000),
    });
    if (!response.ok) return "unavailable";
    const payload: unknown = await response.json();
    if (!isRecord(payload)) return "unavailable";
    if (payload.userId === null) return "invalid";
    return payload.userId === Number(identity.userId) ? "valid" : "invalid";
  } catch {
    return "unavailable";
  }
}

async function authorize(
  request: Request,
  route: Route,
  decision: AccessDecision,
  env: GatewayEnvironment,
): Promise<UserIdentity | null | Response> {
  const mayReadToken = decision.access !== "excluded" && decision.identityMode !== "none";
  const token = mayReadToken ? bearerToken(request, decision.tokenLocation) : null;
  const required = decision.identityMode === "required"
    || decision.access === "authenticated"
    || decision.access === "admin";
  let identity: UserIdentity | null = null;
  let authenticationFailure: Response | null = null;
  if (required && !token) authenticationFailure = apiError(401, "未登录或登录已过期");
  if (token) {
    if (!env.JWT_SECRET || new TextEncoder().encode(env.JWT_SECRET).byteLength < 32) {
      if (required) authenticationFailure = apiError(503, "身份服务尚未配置");
    } else {
      const claims = await verifyAccessToken(token, env.JWT_SECRET);
      identity = claims ? trustedIdentity(claims) : null;
      if (required && !identity) authenticationFailure = apiError(401, "未登录或登录已过期");
    }
  }

  const limited = await applyCoarseRateLimit(request, route, identity, env);
  if (limited) return limited;
  if (authenticationFailure) return authenticationFailure;
  if (!identity || !token) return null;

  const resolution = await resolveActiveIdentity(token, identity, env);
  if (resolution === "unavailable") return required ? apiError(503, "身份服务暂不可用") : null;
  if (resolution !== "valid") return required ? apiError(401, "未登录或登录已过期") : null;

  if (decision.access === "admin" && identity.role !== "ADMIN") {
    return apiError(403, "无权限访问");
  }
  return identity;
}

async function applyCoarseRateLimit(
  request: Request,
  route: Route,
  identity: UserIdentity | null,
  env: GatewayEnvironment,
): Promise<Response | null> {
  if (env.GATEWAY_RATE_LIMIT_ENABLED !== "true") return null;
  if (!env.GATEWAY_RATE_LIMITER) return apiError(503, "请求限流服务尚未配置");

  const actor = identity?.userId || request.headers.get("cf-connecting-ip")?.trim() || "unknown";
  const key = `${route.service}:${route.method}:${route.path}:${actor}`;
  try {
    const result = await env.GATEWAY_RATE_LIMITER.limit({ key });
    if (result.success) return null;
    const limited = apiError(429, "请求过于频繁，请稍后再试");
    limited.headers.set("retry-after", "60");
    return limited;
  } catch {
    return apiError(503, "请求限流服务暂不可用");
  }
}

function correlationId(value: string | null): string {
  const candidate = value?.trim();
  return candidate && SAFE_CORRELATION_ID.test(candidate) ? candidate : crypto.randomUUID();
}

function corsContext(request: Request, env: GatewayEnvironment): CorsContext {
  const origin = request.headers.get("origin");
  const preflight = request.method === "OPTIONS";
  if (!origin) return { origin: null, allowed: true, preflight };

  const configured = (env.CORS_ALLOWED_ORIGINS || "https://dinqor.cn")
    .split(",")
    .map((candidate) => candidate.trim())
    .filter(Boolean);
  const allowed = configured.includes("*") || configured.includes(origin);
  return { origin, allowed, preflight };
}

function appendVary(headers: Headers, values: string[]): void {
  const current = (headers.get("vary") || "").split(",").map((value) => value.trim()).filter(Boolean);
  const merged = new Map(current.map((value) => [value.toLowerCase(), value]));
  for (const value of values) merged.set(value.toLowerCase(), value);
  if (merged.size > 0) headers.set("vary", [...merged.values()].join(", "));
}

function finishResponse(
  response: Response,
  traceId: string,
  cors: CorsContext,
  request: Request,
): Response {
  if (response.status === 101) return response;

  const headers = new Headers(response.headers);
  headers.set("x-trace-id", traceId);
  headers.delete("x-dinqor-service-token");
  headers.delete("x-dinqor-internal-token");
  if (cors.origin && cors.allowed) {
    headers.set("access-control-allow-origin", cors.origin);
    headers.set("access-control-allow-credentials", "true");
    appendVary(headers, ["Origin"]);
  }
  if (cors.preflight && cors.origin && cors.allowed) {
    headers.set("access-control-allow-methods", CORS_METHODS.join(", "));
    const requestedHeaders = request.headers.get("access-control-request-headers");
    if (requestedHeaders) headers.set("access-control-allow-headers", requestedHeaders);
    headers.set("access-control-max-age", "1800");
    appendVary(headers, ["Access-Control-Request-Method", "Access-Control-Request-Headers"]);
  }
  return new Response(response.body, {
    status: response.status,
    statusText: response.statusText,
    headers,
  });
}

function removeUntrustedHeaders(headers: Headers): void {
  for (const header of [
    "x-user-id",
    "x-user-role",
    "x-auth-jti",
    "x-auth-sid",
    "x-authenticated-user-id",
    "x-dinqor-service-token",
    "x-dinqor-internal-token",
    "x-dinqor-client-ip",
    "x-forwarded-for",
    "x-forwarded-host",
    "x-forwarded-proto",
    "x-real-ip",
    "x-trace-id",
    "x-request-id",
  ]) {
    headers.delete(header);
  }
}

function addTrustedIdentity(headers: Headers, identity: UserIdentity | null): void {
  if (!identity) return;
  headers.set("x-user-id", identity.userId);
  if (identity.role) headers.set("x-user-role", identity.role);
  if (identity.jti) headers.set("x-auth-jti", identity.jti);
  if (identity.sid) headers.set("x-auth-sid", identity.sid);
}

function validPreflightMethod(request: Request): boolean {
  const method = request.headers.get("access-control-request-method");
  return !method || CORS_METHODS.includes(method.toUpperCase());
}

export default {
  async fetch(request: Request, env: GatewayEnvironment): Promise<Response> {
    const url = new URL(request.url);
    const traceId = correlationId(request.headers.get("x-trace-id"));
    const requestId = correlationId(request.headers.get("x-request-id"));
    const cors = corsContext(request, env);
    const finish = (response: Response) => finishResponse(response, traceId, cors, request);

    if (!cors.allowed) return finish(apiError(403, "Origin is not allowed"));
    if (cors.preflight) {
      return finish(validPreflightMethod(request)
        ? new Response(null, { status: 204 })
        : apiError(403, "CORS method is not allowed"));
    }
    if (request.method === "GET" && url.pathname === "/healthz") {
      return finish(healthResponse("gateway"));
    }
    if (request.method === "GET" && url.pathname === "/readyz") {
      return finish(await readyResponse("gateway"));
    }

    const route = compiledRoutes.find((candidate) =>
      (candidate.method === "ANY" || candidate.method === request.method)
      && candidate.matcher.test(url.pathname));
    if (!route) return finish(apiError(404, "Route not found"));

    const identity = await authorize(request, route, accessDecision(request.method, url.pathname), env);
    if (identity instanceof Response) return finish(identity);

    const [serviceUrl, serviceToken] = destination(route.service, env);
    const baseUrl = serviceBaseUrl(serviceUrl);
    if (!baseUrl || !serviceToken) {
      return finish(apiError(503, `Service ${route.service} is not configured`));
    }

    const upstreamUrl = new URL(`${url.pathname}${url.search}`, baseUrl);
    try {
      const upstreamRequest = new Request(upstreamUrl, request);
      removeUntrustedHeaders(upstreamRequest.headers);
      addTrustedIdentity(upstreamRequest.headers, identity);
      upstreamRequest.headers.set("x-trace-id", traceId);
      upstreamRequest.headers.set("x-request-id", requestId);
      const edgeClientIp = request.headers.get("cf-connecting-ip");
      if (edgeClientIp) upstreamRequest.headers.set("x-dinqor-client-ip", edgeClientIp);
      upstreamRequest.headers.set("x-dinqor-service-token", serviceToken);
      return finish(await fetch(upstreamRequest));
    } catch {
      return finish(apiError(502, `Service ${route.service} is unavailable`));
    }
  },
};
