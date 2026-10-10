export interface McpOAuthClient {
  id: number;
  clientId: string;
  clientName: string;
  redirectUris: string[];
  status: "ACTIVE" | "REVOKED" | "DISABLED";
  createdAt: string;
}

export interface McpOAuthGrant {
  id: number;
  userId: number;
  clientId: string;
  scopes: string[];
  status: "ACTIVE" | "REVOKED";
  grantedAt: string;
  revokedAt: string | null;
}

export interface McpOAuthToken {
  id: number;
  grantId: number;
  tokenHash: string;
  tokenType: "ACCESS" | "REFRESH";
  tokenFamilyId: string;
  status: "ACTIVE" | "USED" | "REVOKED" | "EXPIRED";
  expiresAt: string;
  usedAt: string | null;
  revokedAt: string | null;
}

export interface McpOAuthRepository {
  insertClient(client: Omit<McpOAuthClient, "id" | "createdAt">): Promise<void>;
  findClient(clientId: string): Promise<McpOAuthClient | null>;
  findGrant(userId: number, clientId: string): Promise<McpOAuthGrant | null>;
  findGrantById(grantId: number): Promise<McpOAuthGrant | null>;
  saveGrant(userId: number, clientId: string, scopes: string[], grantedAt: string): Promise<number>;
  listGrants(userId: number): Promise<McpOAuthGrant[]>;
  revokeGrant(userId: number, clientId: string, revokedAt: string): Promise<void>;
  insertTokens(tokens: Array<Omit<McpOAuthToken, "id" | "usedAt" | "revokedAt">>): Promise<void>;
  findToken(tokenHash: string): Promise<McpOAuthToken | null>;
  markRefreshUsed(tokenHash: string, now: string): Promise<boolean>;
  revokeToken(tokenHash: string, now: string): Promise<void>;
  revokeTokenFamily(familyId: string, now: string): Promise<void>;
  saveState(stateId: string, stateType: "AUTHORIZATION_REQUEST" | "AUTHORIZATION_CODE", payload: string, expiresAt: string): Promise<void>;
  findState(stateId: string, stateType: "AUTHORIZATION_REQUEST" | "AUTHORIZATION_CODE", now: string): Promise<string | null>;
  consumeState(stateId: string, stateType: "AUTHORIZATION_REQUEST" | "AUTHORIZATION_CODE", now: string): Promise<string | null>;
  incrementRateLimit(bucket: string, discriminatorHash: string, now: string, expiresAt: string): Promise<number>;
  cleanupExpired(now: string): Promise<void>;
}
