import type { McpOAuthEnvironment } from "../interfaces/mcp-oauth-environment.js";
import type { McpOAuthClient, McpOAuthGrant, McpOAuthRepository, McpOAuthToken } from "../interfaces/mcp-oauth-repository.js";

const encoder = new TextEncoder();
const SUPPORTED_SCOPES = new Set([
  "blog:read", "blog:write", "csdn:status", "csdn:authorize", "csdn:publish", "publish:dual",
  "juejin:status", "juejin:publish",
]);
const RESOURCE_SCOPES: Record<string, string[]> = {
  "/mcp": ["blog:read", "blog:write"],
  "/api/mcp": ["blog:read", "blog:write"],
  "/api/mcp/csdn": ["csdn:status", "csdn:authorize", "csdn:publish"],
  "/api/mcp/juejin": ["juejin:status", "juejin:publish", "blog:write"],
  "/api/mcp/dual": ["publish:dual", "blog:write", "csdn:publish", "csdn:authorize"],
};

export class McpOAuthError extends Error {
  constructor(readonly error: string, readonly status: number, message: string) {
    super(message);
    this.name = "McpOAuthError";
  }
}

interface AuthorizationRequest {
  requestId: string;
  clientId: string;
  redirectUri: string;
  scopes: string[];
  state: string;
  codeChallenge: string;
  resource: string | null;
  createdAt: string;
}

interface AuthorizationCode {
  grantId: number;
  clientId: string;
  redirectUri: string;
  codeChallenge: string;
  scopes: string[];
}

export class McpOAuthService {
  constructor(
    private readonly repository: McpOAuthRepository,
    private readonly env: McpOAuthEnvironment,
    private readonly now: () => number = Date.now,
  ) {}

  publicBaseUrl(): string {
    const raw = this.env.MCP_OAUTH_PUBLIC_BASE_URL?.trim() || "http://localhost:8790";
    let url: URL;
    try { url = new URL(raw); } catch { throw new McpOAuthError("temporarily_unavailable", 503, "MCP OAuth base URL is invalid"); }
    if (!(["https:", "http:"].includes(url.protocol)) || url.username || url.password || url.search || url.hash
      || (url.protocol === "http:" && !["localhost", "127.0.0.1", "[::1]"].includes(url.hostname))) {
      throw new McpOAuthError("temporarily_unavailable", 503, "MCP OAuth base URL is invalid");
    }
    return url.toString().replace(/\/$/, "");
  }

  async registerClient(clientName: unknown, redirectUris: unknown): Promise<McpOAuthClient> {
    if (typeof clientName !== "string" || !clientName.trim() || clientName.length > 120) {
      throw this.invalid("client_name is required and must be at most 120 characters");
    }
    if (!Array.isArray(redirectUris) || redirectUris.length < 1 || redirectUris.length > 16
      || redirectUris.some((uri) => typeof uri !== "string")) {
      throw this.invalid("redirect_uris must contain between 1 and 16 values");
    }
    const normalized = [...new Set(redirectUris.map((uri) => this.validateRedirectUri(uri as string)))];
    const clientId = this.randomToken(32);
    await this.repository.insertClient({
      clientId, clientName: clientName.trim(), redirectUris: normalized, status: "ACTIVE",
    });
    return { id: 0, clientId, clientName: clientName.trim(), redirectUris: normalized, status: "ACTIVE", createdAt: new Date(this.now()).toISOString() };
  }

  async startAuthorization(input: {
    responseType: string | null; clientId: string | null; redirectUri: string | null; scope: string | null;
    state: string | null; codeChallenge: string | null; codeChallengeMethod: string | null; resource: string | null;
  }): Promise<{ requestId: string; clientName: string; scopes: string[]; consentUrl: string }> {
    this.requireEnabled();
    if (input.responseType !== "code") throw this.invalid("response_type must be code");
    if (!input.state?.trim() || input.state.length > 2048) throw this.invalid("state is required");
    if (input.codeChallengeMethod !== "S256") throw this.invalid("code_challenge_method must be S256");
    if (!input.codeChallenge || !/^[A-Za-z0-9_-]{43,128}$/.test(input.codeChallenge)) {
      throw this.invalid("code_challenge must be a valid S256 value");
    }
    const client = await this.requireActiveClient(input.clientId);
    const redirectUri = this.validateRedirectUri(input.redirectUri);
    if (!client.redirectUris.includes(redirectUri)) throw this.invalid("redirect_uri is not registered");
    const scopes = this.parseScopes(input.scope);
    if (input.resource?.trim()) {
      if (!this.isMcpResource(input.resource)) throw this.invalid("resource is not a DinQorAI MCP endpoint");
      const resourceScopes = this.resourceScopes(input.resource) ?? [];
      if (!resourceScopes.includes("*") && !scopes.every((scope) => resourceScopes.includes(scope))) {
        throw new McpOAuthError("invalid_scope", 400, "requested scope is not available on the selected MCP endpoint");
      }
    }
    const requestId = this.randomToken(24);
    const request: AuthorizationRequest = {
      requestId, clientId: client.clientId, redirectUri, scopes, state: input.state,
      codeChallenge: input.codeChallenge, resource: input.resource?.trim() || null,
      createdAt: new Date(this.now()).toISOString(),
    };
    await this.repository.saveState(requestId, "AUTHORIZATION_REQUEST", JSON.stringify(request),
      this.expiry(this.ttl("MCP_OAUTH_AUTHORIZATION_REQUEST_TTL_SECONDS", 600)));
    return {
      requestId, clientName: client.clientName, scopes,
      consentUrl: `${this.publicBaseUrl()}/profile?mcpOAuthRequest=${encodeURIComponent(requestId)}`,
    };
  }

  async pendingAuthorization(requestId: string, userId: number): Promise<{
    requestId: string; clientName: string; scopes: string[]; resource: string | null; previouslyGranted: string[];
  }> {
    this.requireOwner(userId);
    const request = await this.requireAuthorizationRequest(requestId);
    const client = await this.requireActiveClient(request.clientId);
    const prior = await this.repository.findGrant(userId, client.clientId);
    return {
      requestId: request.requestId, clientName: client.clientName, scopes: request.scopes,
      resource: request.resource, previouslyGranted: prior?.status === "ACTIVE" ? prior.scopes : [],
    };
  }

  async approveAuthorization(requestId: string, userId: number, selectedScopes?: string[]): Promise<string> {
    this.requireOwner(userId);
    const request = await this.requireAuthorizationRequest(requestId);
    const client = await this.requireActiveClient(request.clientId);
    if (!client.redirectUris.includes(request.redirectUri)) throw this.invalid("redirect_uri is not registered");
    const selected = selectedScopes === undefined ? request.scopes : [...new Set(selectedScopes)];
    if (selected.length === 0) throw this.invalid("at least one scope must be selected");
    if (selected.some((scope) => !request.scopes.includes(scope))) {
      throw this.invalid("selected scopes exceed the scopes requested by the client");
    }
    const consumed = await this.repository.consumeState(requestId, "AUTHORIZATION_REQUEST", this.timestamp());
    if (!consumed) throw this.invalid("authorization request is invalid or expired");
    const grantId = await this.repository.saveGrant(userId, client.clientId, selected, this.timestamp());
    const code = this.randomToken(32);
    const codeState: AuthorizationCode = {
      grantId, clientId: client.clientId, redirectUri: request.redirectUri,
      codeChallenge: request.codeChallenge, scopes: request.scopes,
    };
    await this.repository.saveState(await this.sha256(code), "AUTHORIZATION_CODE", JSON.stringify(codeState),
      this.expiry(this.ttl("MCP_OAUTH_AUTHORIZATION_CODE_TTL_SECONDS", 300)));
    return appendQuery(request.redirectUri, { code, state: request.state });
  }

  async denyAuthorization(requestId: string, userId: number): Promise<string> {
    this.requireOwner(userId);
    const request = await this.requireAuthorizationRequest(requestId);
    const consumed = await this.repository.consumeState(requestId, "AUTHORIZATION_REQUEST", this.timestamp());
    if (!consumed) throw this.invalid("authorization request is invalid or expired");
    return appendQuery(request.redirectUri, { error: "access_denied", state: request.state });
  }

  async issueToken(input: {
    grantType: string | null; clientId: string | null; code: string | null; redirectUri: string | null;
    codeVerifier: string | null; refreshToken: string | null;
  }): Promise<{ accessToken: string; refreshToken: string; expiresIn: number; scope: string }> {
    this.requireEnabled();
    const client = await this.requireActiveClient(input.clientId);
    if (input.grantType === "authorization_code") {
      if (!input.code || !input.redirectUri || !input.codeVerifier) throw this.invalid("code, redirect_uri and code_verifier are required");
      const codePayload = await this.repository.consumeState(await this.sha256(input.code), "AUTHORIZATION_CODE", this.timestamp());
      if (!codePayload) throw new McpOAuthError("invalid_grant", 400, "authorization code is invalid or expired");
      const code = this.parseJson<AuthorizationCode>(codePayload);
      if (code.clientId !== client.clientId || code.redirectUri !== this.validateRedirectUri(input.redirectUri)) {
        throw new McpOAuthError("invalid_grant", 400, "authorization code does not belong to this client or redirect URI");
      }
      if (await this.sha256(input.codeVerifier) !== code.codeChallenge) {
        throw new McpOAuthError("invalid_grant", 400, "PKCE verification failed");
      }
      const grant = await this.requireActiveGrant(code.grantId, client.clientId);
      return this.createTokens(grant);
    }
    if (input.grantType === "refresh_token") {
      if (!input.refreshToken) throw this.invalid("refresh_token is required");
      const hash = await this.sha256(input.refreshToken);
      const token = await this.repository.findToken(hash);
      if (!token || token.tokenType !== "REFRESH" || Date.parse(token.expiresAt) < this.now()) {
        throw new McpOAuthError("invalid_grant", 400, "refresh token is invalid or expired");
      }
      const grant = await this.requireActiveGrant(token.grantId, client.clientId);
      if (!await this.repository.markRefreshUsed(hash, this.timestamp())) {
        const current = await this.repository.findToken(hash);
        if (current?.status === "USED") {
          await this.repository.revokeTokenFamily(current.tokenFamilyId, this.timestamp());
          throw new McpOAuthError("invalid_grant", 400, "refresh token was already used");
        }
        throw new McpOAuthError("invalid_grant", 400, "refresh token is invalid or expired");
      }
      return this.createTokens(grant, token.tokenFamilyId);
    }
    throw this.invalid("grant_type must be authorization_code or refresh_token");
  }

  async authenticateBearer(authorization: string | null): Promise<{ userId: number; clientId: string; scopes: string[] } | null> {
    this.requireEnabled();
    if (!authorization?.startsWith("Bearer ")) return null;
    const raw = authorization.slice(7).trim();
    if (!raw) return null;
    const token = await this.repository.findToken(await this.sha256(raw));
    if (!token || token.tokenType !== "ACCESS" || token.status !== "ACTIVE" || Date.parse(token.expiresAt) < this.now()) return null;
    const grant = await this.repository.findGrantById(token.grantId);
    if (!grant || grant.status !== "ACTIVE") return null;
    const client = await this.repository.findClient(grant.clientId);
    if (!client || client.status !== "ACTIVE") return null;
    return { userId: grant.userId, clientId: grant.clientId, scopes: grant.scopes };
  }

  async revokeToken(tokenValue: string | null, clientId: string | null): Promise<void> {
    if (!tokenValue?.trim()) return;
    const token = await this.repository.findToken(await this.sha256(tokenValue));
    if (!token) return;
    const grant = await this.repository.findGrantById(token.grantId);
    if (!grant || grant.clientId !== clientId) return;
    await this.repository.revokeToken(token.tokenHash, this.timestamp());
  }

  async listClientGrants(userId: number): Promise<Array<{ clientId: string; clientName: string; scopes: string[]; grantedAt: string }>> {
    this.requireOwner(userId);
    const grants = await this.repository.listGrants(userId);
    const result = await Promise.all(grants.filter((grant) => grant.status === "ACTIVE").map(async (grant) => {
      const client = await this.repository.findClient(grant.clientId);
      return {
        clientId: grant.clientId, clientName: client?.clientName ?? grant.clientId,
        scopes: grant.scopes, grantedAt: grant.grantedAt,
      };
    }));
    return result;
  }

  async revokeClientGrant(clientId: string, userId: number): Promise<void> {
    this.requireOwner(userId);
    await this.repository.revokeGrant(userId, clientId, this.timestamp());
  }

  async checkRateLimit(bucket: string, discriminator: string): Promise<void> {
    if (!bucket || !discriminator) return;
    const now = this.now();
    const windowSeconds = this.ttl("MCP_OAUTH_RATE_LIMIT_WINDOW_SECONDS", 60);
    const count = await this.repository.incrementRateLimit(bucket, await this.sha256(discriminator),
      new Date(now).toISOString(), new Date(now + windowSeconds * 1000).toISOString());
    if (count > this.ttl("MCP_OAUTH_RATE_LIMIT_MAX_REQUESTS", 20)) {
      throw new McpOAuthError("rate_limited", 429, "too many requests");
    }
  }

  async cleanupExpired(): Promise<void> { await this.repository.cleanupExpired(this.timestamp()); }

  static supportedScopes(): string[] { return [...SUPPORTED_SCOPES].sort(); }
  static scopesForResource(resource: string | null): string[] | null {
    if (!resource) return null;
    try { return RESOURCE_SCOPES[new URL(resource).pathname] ?? null; } catch { return null; }
  }

  private async createTokens(grant: McpOAuthGrant, familyId = this.randomToken(24)) {
    const accessToken = this.randomToken(48);
    const refreshToken = this.randomToken(48);
    const now = this.now();
    const accessTtl = this.ttl("MCP_OAUTH_ACCESS_TOKEN_TTL_SECONDS", 3600);
    const refreshTtl = this.ttl("MCP_OAUTH_REFRESH_TOKEN_TTL_SECONDS", 2_592_000);
    const toRecord = (raw: string, tokenType: "ACCESS" | "REFRESH", ttl: number): Omit<McpOAuthToken, "id" | "usedAt" | "revokedAt"> => ({
      grantId: grant.id, tokenHash: "", tokenType, tokenFamilyId: familyId,
      status: "ACTIVE", expiresAt: new Date(now + ttl * 1000).toISOString(),
    });
    const access = toRecord(accessToken, "ACCESS", accessTtl);
    const refresh = toRecord(refreshToken, "REFRESH", refreshTtl);
    access.tokenHash = await this.sha256(accessToken);
    refresh.tokenHash = await this.sha256(refreshToken);
    await this.repository.insertTokens([access, refresh]);
    return { accessToken, refreshToken, expiresIn: accessTtl, scope: [...grant.scopes].sort().join(" ") };
  }

  private async requireActiveClient(clientId: string | null): Promise<McpOAuthClient> {
    if (!clientId?.trim()) throw this.invalid("client_id is required");
    const client = await this.repository.findClient(clientId);
    if (!client || client.status !== "ACTIVE") throw new McpOAuthError("invalid_client", 401, "OAuth client is not registered or active");
    return client;
  }

  private async requireActiveGrant(grantId: number, clientId: string): Promise<McpOAuthGrant> {
    const grant = await this.repository.findGrantById(grantId);
    if (!grant || grant.status !== "ACTIVE" || grant.clientId !== clientId) {
      throw new McpOAuthError("invalid_grant", 400, "authorization grant is inactive");
    }
    return grant;
  }

  private async requireAuthorizationRequest(requestId: string): Promise<AuthorizationRequest> {
    if (!requestId?.trim()) throw this.invalid("authorization request is required");
    const payload = await this.repository.findState(requestId, "AUTHORIZATION_REQUEST", this.timestamp());
    if (!payload) throw this.invalid("authorization request is invalid or expired");
    return this.parseJson<AuthorizationRequest>(payload);
  }

  private parseScopes(value: string | null): string[] {
    if (!value?.trim()) throw this.invalid("scope is required");
    const scopes = [...new Set(value.trim().split(/\s+/).filter(Boolean))].sort();
    if (!scopes.length || scopes.some((scope) => !SUPPORTED_SCOPES.has(scope))) {
      throw new McpOAuthError("invalid_scope", 400, "requested scope is unsupported");
    }
    return scopes;
  }

  private validateRedirectUri(value: string | null): string {
    if (!value) throw this.invalid("redirect_uri is required");
    let url: URL;
    try { url = new URL(value); } catch { throw this.invalid("redirect_uri is invalid"); }
    const secure = url.protocol === "https:" && Boolean(url.hostname);
    const loopback = url.protocol === "http:" && ["127.0.0.1", "localhost", "[::1]"].includes(url.hostname);
    if ((!secure && !loopback) || url.hash || url.username || url.password) {
      throw this.invalid("redirect_uri must be HTTPS or a loopback URI");
    }
    return url.toString();
  }

  private isMcpResource(resource: string): boolean {
    const base = this.publicBaseUrl();
    return ["/mcp", "/api/mcp", "/api/mcp/csdn", "/api/mcp/dual", "/api/mcp/juejin"]
      .some((path) => resource === `${base}${path}`);
  }

  private resourceScopes(resource: string): string[] | null { return McpOAuthService.scopesForResource(resource); }
  private requireEnabled(): void {
    if (this.env.MCP_OAUTH_ENABLED?.toLowerCase() !== "true") {
      throw new McpOAuthError("temporarily_unavailable", 503, "MCP OAuth is disabled");
    }
  }
  private requireOwner(userId: number): void {
    if (!Number.isSafeInteger(userId) || userId <= 0) throw new McpOAuthError("login_required", 401, "DinQorAI login is required");
  }
  private ttl(name: keyof McpOAuthEnvironment, fallback: number): number {
    const value = Number(this.env[name] ?? fallback);
    return Number.isSafeInteger(value) && value >= 1 && value <= 31_536_000 ? value : fallback;
  }
  private expiry(seconds: number): string { return new Date(this.now() + seconds * 1000).toISOString(); }
  private timestamp(): string { return new Date(this.now()).toISOString(); }
  private randomToken(bytes: number): string {
    const raw = crypto.getRandomValues(new Uint8Array(bytes));
    let binary = "";
    for (const value of raw) binary += String.fromCharCode(value);
    return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/, "");
  }
  private async sha256(value: string): Promise<string> {
    const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", encoder.encode(value)));
    let binary = "";
    for (const byte of digest) binary += String.fromCharCode(byte);
    return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/, "");
  }
  private parseJson<T>(value: string): T {
    try { return JSON.parse(value) as T; } catch { throw this.invalid("OAuth state is invalid"); }
  }
  private invalid(message: string): McpOAuthError { return new McpOAuthError("invalid_request", 400, message); }
}

function appendQuery(uri: string, values: Record<string, string>): string {
  return `${uri}${uri.includes("?") ? "&" : "?"}${Object.entries(values)
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(value)}`).join("&")}`;
}
