import assert from "node:assert/strict";
import test from "node:test";
import { handleMcpOAuthRequest } from "../.test-build/controllers/mcp-oauth-controller.js";
import { McpOAuthError, McpOAuthService } from "../.test-build/services/mcp-oauth-service.js";

class MemoryOAuthRepository {
  constructor() {
    this.clients = new Map();
    this.grants = new Map();
    this.tokens = new Map();
    this.states = new Map();
    this.rates = new Map();
    this.nextGrantId = 1;
    this.nextTokenId = 1;
  }
  async insertClient(client) {
    this.clients.set(client.clientId, { ...client, id: this.clients.size + 1, createdAt: new Date().toISOString() });
  }
  async findClient(clientId) { return this.clients.get(clientId) ?? null; }
  async findGrant(userId, clientId) { return this.grants.get(`${userId}:${clientId}`) ?? null; }
  async findGrantById(id) { return [...this.grants.values()].find((grant) => grant.id === id) ?? null; }
  async saveGrant(userId, clientId, scopes, grantedAt) {
    const key = `${userId}:${clientId}`;
    const row = { id: this.grants.get(key)?.id ?? this.nextGrantId++, userId, clientId, scopes: [...scopes], status: "ACTIVE", grantedAt, revokedAt: null };
    this.grants.set(key, row);
    return row.id;
  }
  async listGrants(userId) { return [...this.grants.values()].filter((grant) => grant.userId === userId); }
  async revokeGrant(userId, clientId, revokedAt) {
    const grant = this.grants.get(`${userId}:${clientId}`);
    if (grant?.status === "ACTIVE") Object.assign(grant, { status: "REVOKED", revokedAt });
  }
  async insertTokens(tokens) {
    for (const token of tokens) this.tokens.set(token.tokenHash, { ...token, id: this.nextTokenId++, usedAt: null, revokedAt: null });
  }
  async findToken(hash) { return this.tokens.get(hash) ?? null; }
  async markRefreshUsed(hash, now) {
    const token = this.tokens.get(hash);
    if (!token || token.tokenType !== "REFRESH" || token.status !== "ACTIVE" || Date.parse(token.expiresAt) < Date.parse(now)) return false;
    Object.assign(token, { status: "USED", usedAt: now });
    return true;
  }
  async revokeToken(hash, now) {
    const token = this.tokens.get(hash);
    if (token) Object.assign(token, { status: "REVOKED", revokedAt: now });
  }
  async revokeTokenFamily(family, now) {
    for (const token of this.tokens.values()) {
      if (token.tokenFamilyId === family && token.status === "ACTIVE") Object.assign(token, { status: "REVOKED", revokedAt: now });
    }
  }
  async saveState(id, type, payload, expiresAt) { this.states.set(`${type}:${id}`, { payload, expiresAt }); }
  async findState(id, type, now) {
    const state = this.states.get(`${type}:${id}`);
    return state && Date.parse(state.expiresAt) > Date.parse(now) ? state.payload : null;
  }
  async consumeState(id, type, now) {
    const key = `${type}:${id}`;
    const state = this.states.get(key);
    if (!state || Date.parse(state.expiresAt) <= Date.parse(now)) return null;
    this.states.delete(key);
    return state.payload;
  }
  async incrementRateLimit(bucket, id, now, expiresAt) {
    const key = `${bucket}:${id}`;
    let row = this.rates.get(key);
    if (!row || Date.parse(row.expiresAt) <= Date.parse(now)) row = { count: 0, expiresAt };
    row.count++;
    this.rates.set(key, row);
    return row.count;
  }
  async cleanupExpired(now) {
    for (const [key, value] of this.states) if (Date.parse(value.expiresAt) <= Date.parse(now)) this.states.delete(key);
    for (const [key, value] of this.rates) if (Date.parse(value.expiresAt) <= Date.parse(now)) this.rates.delete(key);
  }
}

function serviceFixture(overrides = {}) {
  const repository = new MemoryOAuthRepository();
  const env = {
    MCP_OAUTH_ENABLED: "true",
    MCP_OAUTH_PUBLIC_BASE_URL: "https://dinqor.cn",
    MCP_OAUTH_RATE_LIMIT_MAX_REQUESTS: "2",
    ...overrides,
  };
  return { repository, service: new McpOAuthService(repository, env) };
}

async function challenge(verifier) {
  return Buffer.from(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier))).toString("base64url");
}

test("MCP OAuth performs dynamic registration, user consent, one-time PKCE code exchange, and rotating refresh", async () => {
  const { repository, service } = serviceFixture();
  const client = await service.registerClient("Local MCP", ["https://localhost.example/callback", "http://127.0.0.1:5555/cb"]);
  const verifier = "a".repeat(43);
  const auth = await service.startAuthorization({
    responseType: "code", clientId: client.clientId, redirectUri: "https://localhost.example/callback",
    scope: "blog:read blog:write", state: "client-state", codeChallenge: await challenge(verifier),
    codeChallengeMethod: "S256", resource: "https://dinqor.cn/api/mcp",
  });
  assert.match(auth.consentUrl, /^https:\/\/dinqor\.cn\/profile\?mcpOAuthRequest=/);
  assert.deepEqual((await service.pendingAuthorization(auth.requestId, 905)).previouslyGranted, []);
  const callback = await service.approveAuthorization(auth.requestId, 905, ["blog:read"]);
  const code = new URL(callback).searchParams.get("code");
  assert.equal(new URL(callback).searchParams.get("state"), "client-state");
  const pair = await service.issueToken({
    grantType: "authorization_code", clientId: client.clientId, code,
    redirectUri: "https://localhost.example/callback", codeVerifier: verifier, refreshToken: null,
  });
  assert.equal(pair.scope, "blog:read");
  assert.equal((await service.authenticateBearer(`Bearer ${pair.accessToken}`)).userId, 905);
  await assert.rejects(service.issueToken({
    grantType: "authorization_code", clientId: client.clientId, code,
    redirectUri: "https://localhost.example/callback", codeVerifier: verifier, refreshToken: null,
  }), (error) => error instanceof McpOAuthError && error.error === "invalid_grant");

  const rotated = await service.issueToken({
    grantType: "refresh_token", clientId: client.clientId, code: null,
    redirectUri: null, codeVerifier: null, refreshToken: pair.refreshToken,
  });
  assert.equal(rotated.scope, "blog:read");
  await assert.rejects(service.issueToken({
    grantType: "refresh_token", clientId: client.clientId, code: null,
    redirectUri: null, codeVerifier: null, refreshToken: pair.refreshToken,
  }), (error) => error instanceof McpOAuthError && error.error === "invalid_grant");
  assert.equal(await service.authenticateBearer(`Bearer ${rotated.accessToken}`), null);
  assert.ok([...repository.tokens.values()].every((token) => token.status !== "ACTIVE"));
});

test("MCP OAuth refuses scopes outside a resource and refresh replay invalidates the token family", async () => {
  const { repository, service } = serviceFixture();
  const client = await service.registerClient("Blog only", ["http://localhost:53111/callback"]);
  await assert.rejects(service.startAuthorization({
    responseType: "code", clientId: client.clientId, redirectUri: "http://localhost:53111/callback",
    scope: "csdn:publish", state: "s", codeChallenge: "A".repeat(43), codeChallengeMethod: "S256",
    resource: "https://dinqor.cn/api/mcp",
  }), (error) => error instanceof McpOAuthError && error.error === "invalid_scope");

  const unauthorized = new McpOAuthService(repository, { MCP_OAUTH_ENABLED: "false" });
  await assert.rejects(unauthorized.authenticateBearer("Bearer token"), /disabled/);
});

test("MCP OAuth controller returns discovery metadata, form tokens and OAuth error responses without envelopes", async () => {
  const { service } = serviceFixture({ MCP_OAUTH_RATE_LIMIT_MAX_REQUESTS: "1" });
  const env = {};
  const metadata = await handleMcpOAuthRequest(new Request("https://worker.test/.well-known/oauth-authorization-server"), env, service);
  assert.equal(metadata.status, 200);
  assert.equal((await metadata.json()).token_endpoint, "https://dinqor.cn/api/mcp/oauth/token");

  const badResource = await handleMcpOAuthRequest(new Request(
    "https://worker.test/.well-known/oauth-protected-resource?resource=https%3A%2F%2Fevil.example%2Fmcp",
  ), env, service);
  assert.equal(badResource.status, 400);
  assert.equal(badResource.headers.get("cache-control"), "no-store");
  assert.equal((await badResource.json()).error, "invalid_request");

  const registration = await handleMcpOAuthRequest(new Request("https://worker.test/api/mcp/oauth/register", {
    method: "POST", headers: { "content-type": "application/json", "cf-connecting-ip": "198.51.100.9" },
    body: JSON.stringify({ client_name: "Tool host", redirect_uris: ["https://host.example/callback"] }),
  }), env, service);
  assert.equal(registration.status, 201);
  const response = await registration.json();
  assert.equal(response.token_endpoint_auth_method, "none");
  assert.equal("code" in response, false);

  const rateLimited = await handleMcpOAuthRequest(new Request("https://worker.test/api/mcp/oauth/register", {
    method: "POST", headers: { "content-type": "application/json", "cf-connecting-ip": "198.51.100.9" },
    body: JSON.stringify({ client_name: "Tool host 2", redirect_uris: ["https://host.example/callback"] }),
  }), env, service);
  assert.equal(rateLimited.status, 429);
});
