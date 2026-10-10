import type { D1Binding } from "@dinqor/worker-runtime";
import type {
  McpOAuthClient, McpOAuthGrant, McpOAuthRepository, McpOAuthToken,
} from "../interfaces/mcp-oauth-repository.js";

interface ClientRow extends Omit<McpOAuthClient, "redirectUris"> { redirectUrisJson: string; }
interface GrantRow extends Omit<McpOAuthGrant, "scopes"> { scopesText: string; }

/** Platform-owned D1 persistence for MCP OAuth clients, grants, token hashes and expiring state. */
export class D1McpOAuthMapper implements McpOAuthRepository {
  constructor(private readonly db: D1Binding) {}

  async insertClient(client: Omit<McpOAuthClient, "id" | "createdAt">): Promise<void> {
    const result = await this.db.prepare(`
      INSERT INTO mcp_oauth_client (client_id, client_name, redirect_uris, status)
      VALUES (?, ?, ?, ?)
    `).bind(client.clientId, client.clientName, JSON.stringify(client.redirectUris), client.status).run();
    if (!result.success) throw new Error("MCP OAuth client write failed");
  }

  async findClient(clientId: string): Promise<McpOAuthClient | null> {
    const row = await this.db.prepare(`
      SELECT id, client_id AS clientId, client_name AS clientName, redirect_uris AS redirectUrisJson,
        status, created_at AS createdAt
      FROM mcp_oauth_client WHERE client_id = ?
    `).bind(clientId).first<ClientRow>();
    return row ? { ...row, redirectUris: parseStringArray(row.redirectUrisJson) } : null;
  }

  async findGrant(userId: number, clientId: string): Promise<McpOAuthGrant | null> {
    const row = await this.db.prepare(`
      SELECT id, user_id AS userId, client_id AS clientId, scopes AS scopesText, status,
        granted_at AS grantedAt, revoked_at AS revokedAt
      FROM mcp_oauth_grant WHERE user_id = ? AND client_id = ?
    `).bind(userId, clientId).first<GrantRow>();
    return row ? { ...row, scopes: splitScopes(row.scopesText) } : null;
  }

  async findGrantById(grantId: number): Promise<McpOAuthGrant | null> {
    const row = await this.db.prepare(`
      SELECT id, user_id AS userId, client_id AS clientId, scopes AS scopesText, status,
        granted_at AS grantedAt, revoked_at AS revokedAt
      FROM mcp_oauth_grant WHERE id = ?
    `).bind(grantId).first<GrantRow>();
    return row ? { ...row, scopes: splitScopes(row.scopesText) } : null;
  }

  async saveGrant(userId: number, clientId: string, scopes: string[], grantedAt: string): Promise<number> {
    const row = await this.db.prepare(`
      INSERT INTO mcp_oauth_grant (user_id, client_id, scopes, status, granted_at, revoked_at)
      VALUES (?, ?, ?, 'ACTIVE', ?, NULL)
      ON CONFLICT(user_id, client_id) DO UPDATE SET scopes = excluded.scopes, status = 'ACTIVE',
        granted_at = excluded.granted_at, revoked_at = NULL,
        updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
      RETURNING id
    `).bind(userId, clientId, joinScopes(scopes), grantedAt).first<{ id: number }>();
    if (!row || !Number.isSafeInteger(row.id)) throw new Error("MCP OAuth consent write failed");
    return row.id;
  }

  async listGrants(userId: number): Promise<McpOAuthGrant[]> {
    const rows = await this.db.prepare(`
      SELECT id, user_id AS userId, client_id AS clientId, scopes AS scopesText, status,
        granted_at AS grantedAt, revoked_at AS revokedAt
      FROM mcp_oauth_grant WHERE user_id = ? AND status = 'ACTIVE' ORDER BY granted_at DESC
    `).bind(userId).all<GrantRow>();
    return rows.results.map((row) => ({ ...row, scopes: splitScopes(row.scopesText) }));
  }

  async revokeGrant(userId: number, clientId: string, revokedAt: string): Promise<void> {
    const result = await this.db.prepare(`
      UPDATE mcp_oauth_grant SET status = 'REVOKED', revoked_at = ?,
        updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
      WHERE user_id = ? AND client_id = ? AND status = 'ACTIVE'
    `).bind(revokedAt, userId, clientId).run();
    if (!result.success) throw new Error("MCP OAuth consent revocation failed");
  }

  async insertTokens(tokens: Array<Omit<McpOAuthToken, "id" | "usedAt" | "revokedAt">>): Promise<void> {
    const results = await this.db.batch(tokens.map((token) => this.db.prepare(`
      INSERT INTO mcp_oauth_token (grant_id, token_hash, token_type, token_family_id, status, expires_at)
      VALUES (?, ?, ?, ?, ?, ?)
    `).bind(token.grantId, token.tokenHash, token.tokenType, token.tokenFamilyId, token.status, token.expiresAt)));
    if (results.some((result) => !result.success)) throw new Error("MCP OAuth token write failed");
  }

  findToken(tokenHash: string): Promise<McpOAuthToken | null> {
    return this.db.prepare(`
      SELECT id, grant_id AS grantId, token_hash AS tokenHash, token_type AS tokenType,
        token_family_id AS tokenFamilyId, status, expires_at AS expiresAt,
        used_at AS usedAt, revoked_at AS revokedAt
      FROM mcp_oauth_token WHERE token_hash = ?
    `).bind(tokenHash).first<McpOAuthToken>();
  }

  async markRefreshUsed(tokenHash: string, now: string): Promise<boolean> {
    const result = await this.db.prepare(`
      UPDATE mcp_oauth_token SET status = 'USED', used_at = ?
      WHERE token_hash = ? AND token_type = 'REFRESH' AND status = 'ACTIVE' AND expires_at >= ?
    `).bind(now, tokenHash, now).run();
    if (!result.success) throw new Error("MCP OAuth refresh rotation failed");
    return (result.meta?.changes ?? 0) === 1;
  }

  async revokeToken(tokenHash: string, now: string): Promise<void> {
    const result = await this.db.prepare(`
      UPDATE mcp_oauth_token SET status = 'REVOKED', revoked_at = ? WHERE token_hash = ?
    `).bind(now, tokenHash).run();
    if (!result.success) throw new Error("MCP OAuth token revocation failed");
  }

  async revokeTokenFamily(familyId: string, now: string): Promise<void> {
    const result = await this.db.prepare(`
      UPDATE mcp_oauth_token SET status = 'REVOKED', revoked_at = ?
      WHERE token_family_id = ? AND status = 'ACTIVE'
    `).bind(now, familyId).run();
    if (!result.success) throw new Error("MCP OAuth token family revocation failed");
  }

  async saveState(stateId: string, stateType: "AUTHORIZATION_REQUEST" | "AUTHORIZATION_CODE", payload: string, expiresAt: string): Promise<void> {
    const result = await this.db.prepare(`
      INSERT INTO mcp_oauth_ephemeral_state (state_id, state_type, payload, expires_at)
      VALUES (?, ?, ?, ?)
    `).bind(stateId, stateType, payload, expiresAt).run();
    if (!result.success) throw new Error("MCP OAuth state write failed");
  }

  async findState(stateId: string, stateType: "AUTHORIZATION_REQUEST" | "AUTHORIZATION_CODE", now: string): Promise<string | null> {
    const row = await this.db.prepare(`
      SELECT payload FROM mcp_oauth_ephemeral_state
      WHERE state_id = ? AND state_type = ? AND expires_at > ?
    `).bind(stateId, stateType, now).first<{ payload: string }>();
    return row?.payload ?? null;
  }

  async consumeState(stateId: string, stateType: "AUTHORIZATION_REQUEST" | "AUTHORIZATION_CODE", now: string): Promise<string | null> {
    const row = await this.db.prepare(`
      DELETE FROM mcp_oauth_ephemeral_state
      WHERE state_id = ? AND state_type = ? AND expires_at > ?
      RETURNING payload
    `).bind(stateId, stateType, now).first<{ payload: string }>();
    return row?.payload ?? null;
  }

  async incrementRateLimit(bucket: string, discriminatorHash: string, now: string, expiresAt: string): Promise<number> {
    const row = await this.db.prepare(`
      INSERT INTO mcp_oauth_rate_limit (bucket, discriminator_hash, request_count, window_started_at, window_expires_at)
      VALUES (?, ?, 1, ?, ?)
      ON CONFLICT(bucket, discriminator_hash) DO UPDATE SET
        request_count = CASE WHEN mcp_oauth_rate_limit.window_expires_at <= excluded.window_started_at
          THEN 1 ELSE mcp_oauth_rate_limit.request_count + 1 END,
        window_started_at = CASE WHEN mcp_oauth_rate_limit.window_expires_at <= excluded.window_started_at
          THEN excluded.window_started_at ELSE mcp_oauth_rate_limit.window_started_at END,
        window_expires_at = CASE WHEN mcp_oauth_rate_limit.window_expires_at <= excluded.window_started_at
          THEN excluded.window_expires_at ELSE mcp_oauth_rate_limit.window_expires_at END
      RETURNING request_count AS requestCount
    `).bind(bucket, discriminatorHash, now, expiresAt).first<{ requestCount: number }>();
    if (!row || !Number.isSafeInteger(row.requestCount)) throw new Error("MCP OAuth rate limit update failed");
    return row.requestCount;
  }

  async cleanupExpired(now: string): Promise<void> {
    const results = await this.db.batch([
      this.db.prepare("DELETE FROM mcp_oauth_ephemeral_state WHERE expires_at <= ?").bind(now),
      this.db.prepare("DELETE FROM mcp_oauth_rate_limit WHERE window_expires_at <= ?").bind(now),
      this.db.prepare("UPDATE mcp_oauth_token SET status = 'EXPIRED' WHERE status = 'ACTIVE' AND expires_at <= ?").bind(now),
    ]);
    if (results.some((result) => !result.success)) throw new Error("MCP OAuth expiry cleanup failed");
  }
}

function parseStringArray(value: string): string[] {
  try {
    const parsed: unknown = JSON.parse(value);
    return Array.isArray(parsed) && parsed.every((item) => typeof item === "string") ? parsed : [];
  } catch { return []; }
}

function splitScopes(value: string): string[] { return [...new Set(value.trim().split(/\s+/).filter(Boolean))].sort(); }
function joinScopes(value: string[]): string { return [...new Set(value)].sort().join(" "); }
