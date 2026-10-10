const tokenEncoder = new TextEncoder();
const MAX_JWT_BYTES = 8192;

export interface AccessTokenClaims {
  sub: string;
  exp: number;
  type?: string;
  [claim: string]: unknown;
}

function decodeBase64Url(value: string): Uint8Array {
  if (!/^[A-Za-z0-9_-]+$/.test(value)) throw new Error("Invalid base64url");
  const base64 = value.replace(/-/g, "+").replace(/_/g, "/")
    .padEnd(Math.ceil(value.length / 4) * 4, "=");
  const binary = atob(base64);
  return Uint8Array.from(binary, (character) => character.charCodeAt(0));
}

function encodeBase64Url(value: Uint8Array): string {
  let binary = "";
  for (const byte of value) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function digestName(algorithm: string): "SHA-256" | "SHA-384" | "SHA-512" | null {
  if (algorithm === "HS256") return "SHA-256";
  if (algorithm === "HS384") return "SHA-384";
  if (algorithm === "HS512") return "SHA-512";
  return null;
}

function algorithmForKeyLength(byteLength: number): "HS256" | "HS384" | "HS512" {
  if (byteLength >= 64) return "HS512";
  if (byteLength >= 48) return "HS384";
  if (byteLength >= 32) return "HS256";
  throw new Error("JWT secret must contain at least 32 bytes");
}

function parseJsonObject(bytes: Uint8Array): Record<string, unknown> {
  const value: unknown = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new Error("Invalid JWT object");
  }
  return value as Record<string, unknown>;
}

function hasValidSubject(value: unknown): value is string {
  return typeof value === "string"
    && /^[1-9]\d{0,17}$/.test(value)
    && Number.isSafeInteger(Number(value));
}

async function verifyTokenType(
  token: string,
  secret: string,
  expectedType: "access" | "refresh",
  nowSeconds = Math.floor(Date.now() / 1000),
): Promise<AccessTokenClaims | null> {
  if (!secret || tokenEncoder.encode(secret).byteLength < 32
    || !token || tokenEncoder.encode(token).byteLength > MAX_JWT_BYTES) {
    return null;
  }

  const parts = token.split(".");
  if (parts.length !== 3 || parts.some((part) => !part)) return null;

  try {
    const header = parseJsonObject(decodeBase64Url(parts[0]));
    const hash = typeof header.alg === "string" ? digestName(header.alg) : null;
    if (!hash || (header.typ !== undefined && header.typ !== "JWT")) return null;

    const claims = parseJsonObject(decodeBase64Url(parts[1]));
    if (!hasValidSubject(claims.sub)
      || typeof claims.exp !== "number"
      || !Number.isSafeInteger(claims.exp)
      || claims.exp <= nowSeconds
      || (typeof claims.nbf === "number" && claims.nbf > nowSeconds)
      || claims.type !== expectedType) {
      return null;
    }

    const keyBytes = tokenEncoder.encode(secret);
    const keyBuffer = new Uint8Array(keyBytes.byteLength);
    keyBuffer.set(keyBytes);
    const key = await crypto.subtle.importKey(
      "raw",
      keyBuffer.buffer as ArrayBuffer,
      { name: "HMAC", hash },
      false,
      ["verify"],
    );
    const signature = decodeBase64Url(parts[2]);
    const signatureBuffer = new Uint8Array(signature.byteLength);
    signatureBuffer.set(signature);
    const validSignature = await crypto.subtle.verify(
      "HMAC",
      key,
      signatureBuffer.buffer as ArrayBuffer,
      tokenEncoder.encode(parts[0] + "." + parts[1]),
    );
    return validSignature ? claims as AccessTokenClaims : null;
  } catch {
    return null;
  }
}

/** Verifies the HMAC access tokens issued by the existing identity service. */
export function verifyAccessToken(
  token: string,
  secret: string,
  nowSeconds = Math.floor(Date.now() / 1000),
): Promise<AccessTokenClaims | null> {
  return verifyTokenType(token, secret, "access", nowSeconds);
}

/** Verifies stateless refresh JWTs used while the legacy refresh protocol is enabled. */
export function verifyRefreshToken(
  token: string,
  secret: string,
  nowSeconds = Math.floor(Date.now() / 1000),
): Promise<AccessTokenClaims | null> {
  return verifyTokenType(token, secret, "refresh", nowSeconds);
}

/** Signs a Java-compatible HMAC JWT using the key-strength algorithm JJWT selects. */
export async function signJwtToken(
  subject: string,
  secret: string,
  claims: Record<string, unknown>,
  ttlSeconds: number,
  nowSeconds = Math.floor(Date.now() / 1000),
): Promise<string> {
  const secretBytes = tokenEncoder.encode(secret);
  if (!/^[1-9]\d{0,17}$/.test(subject)
    || !Number.isSafeInteger(Number(subject))
    || !Number.isSafeInteger(ttlSeconds)
    || ttlSeconds <= 0
    || !Number.isSafeInteger(nowSeconds)
    || !Number.isSafeInteger(nowSeconds + ttlSeconds)) {
    throw new Error("Invalid JWT subject or lifetime");
  }
  const algorithm = algorithmForKeyLength(secretBytes.byteLength);
  const hash = digestName(algorithm);
  if (!hash) throw new Error("Unsupported JWT signature algorithm");

  const header = encodeBase64Url(tokenEncoder.encode(JSON.stringify({ alg: algorithm, typ: "JWT" })));
  const payload = encodeBase64Url(tokenEncoder.encode(JSON.stringify({
    ...claims,
    sub: subject,
    jti: crypto.randomUUID(),
    iat: nowSeconds,
    exp: nowSeconds + ttlSeconds,
  })));
  const unsignedToken = header + "." + payload;
  const keyBytes = new Uint8Array(secretBytes.byteLength);
  keyBytes.set(secretBytes);
  const key = await crypto.subtle.importKey(
    "raw",
    keyBytes.buffer as ArrayBuffer,
    { name: "HMAC", hash },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign("HMAC", key, tokenEncoder.encode(unsignedToken));
  return unsignedToken + "." + encodeBase64Url(new Uint8Array(signature));
}

export interface D1Binding {
  prepare(query: string): D1PreparedStatement;
  batch(statements: D1PreparedStatement[]): Promise<D1Result[]>;
}

/** HTTP entrypoint exposed by a Cloudflare service binding. */
export interface WorkerServiceBinding {
  fetch(request: Request): Promise<Response>;
}

/** Uses a same-account service binding when present and keeps HTTPS as the cross-account fallback. */
export function fetchWithServiceBinding(
  binding: WorkerServiceBinding | undefined,
  input: string | URL | Request,
  init?: RequestInit,
  fallback: typeof fetch = fetch,
): Promise<Response> {
  if (!binding) return fallback(input, init);
  const request = input instanceof Request ? input : new Request(input, init);
  return binding.fetch(request);
}

export interface D1PreparedStatement {
  bind(...values: (string | number | null)[]): D1PreparedStatement;
  first<T = Record<string, unknown>>(columnName?: string): Promise<T | null>;
  all<T = Record<string, unknown>>(): Promise<{ results: T[] }>;
  run(): Promise<D1Result>;
}

export interface D1Result {
  success: boolean;
  meta: { changes: number };
  results?: Array<Record<string, unknown>>;
}

export interface ServiceEnvironment {
  DB?: D1Binding;
  INTERNAL_SERVICE_TOKEN?: string;
}

export function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}

export function healthResponse(service: string): Response {
  return jsonResponse({ status: "ok", service });
}

export async function readyResponse(
  service: string,
  database?: D1Binding,
): Promise<Response> {
  if (!database) return jsonResponse({ status: "ready", service });

  try {
    const result = await database.prepare("SELECT 1 AS ok").first<{ ok: number }>();
    if (result?.ok !== 1) throw new Error("D1 readiness query returned no result");
    return jsonResponse({ status: "ready", service, database: "ok" });
  } catch {
    return jsonResponse(
      { status: "not_ready", service, database: "unavailable" },
      503,
    );
  }
}

export function apiError(
  status: number,
  message: string,
  data: unknown = null,
): Response {
  return jsonResponse({ code: status, message, data }, status);
}

export type BusinessRequestHandler<E extends ServiceEnvironment> = (
  request: Request,
  env: E,
  context: WorkerExecutionContextLike,
) => Promise<Response | null>;

export interface WorkerExecutionContextLike {
  waitUntil(promise: Promise<unknown>): void;
}

export function createBusinessService<E extends ServiceEnvironment = ServiceEnvironment>(
  service: string,
  handleBusinessRequest?: BusinessRequestHandler<E>,
) {
  return {
    async fetch(request: Request, env: E, context: WorkerExecutionContextLike): Promise<Response> {
      const url = new URL(request.url);
      if (request.method === "GET" && url.pathname === "/healthz") {
        return healthResponse(service);
      }
      if (request.method === "GET" && url.pathname === "/readyz") {
        return readyResponse(service, env.DB);
      }

      const expected = env.INTERNAL_SERVICE_TOKEN;
      const received = request.headers.get("x-dinqor-service-token");
      if (!expected || received !== expected) {
        return apiError(401, "Service authentication required");
      }

      if (handleBusinessRequest) {
        const response = await handleBusinessRequest(request, env, context);
        if (response) return response;
      }

      return apiError(501, `${service} business routes have not been migrated`);
    },
  };
}

