import type { IdentityUser } from "./identity-repository.js";

export interface RefreshSessionRecord {
  sid: string;
  accountId: number;
  familyId: string;
  currentTokenId: string;
  currentTokenHash: string;
  generation: number;
  status: "ACTIVE" | "REVOKED" | "EXPIRED";
  issuedAt: string;
  lastRotatedAt: string | null;
  expiresAt: string;
  revokedAt: string | null;
  revokedReason: string | null;
}

export interface RefreshTokenHistoryRecord {
  tokenId: string;
  sid: string;
  tokenHash: string;
  generation: number;
  usedAt: string;
  successorTokenId: string | null;
  graceExpiresAt: string;
  retainedUntil: string;
  reuseDetectedAt: string | null;
  successorTokenCiphertext: string | null;
  successorCiphertextExpiresAt: string | null;
}

export interface RefreshRotationWrite {
  session: RefreshSessionRecord;
  previousTokenId: string;
  previousTokenHash: string;
  previousGeneration: number;
  usedAt: string;
  graceExpiresAt: string;
  retainedUntil: string;
  successorTokenId: string;
  successorTokenCiphertext: string;
}

export interface IdentitySessionRepository {
  findActiveUserById(userId: number): Promise<IdentityUser | null>;
  recordTokenRevocation(tokenFingerprint: string, expiresAt: number, revokedAt: number): Promise<void>;
  deleteExpiredTokenRevocations(now: number): Promise<void>;
  createRefreshSession(session: RefreshSessionRecord): Promise<void>;
  findRefreshSession(sid: string): Promise<RefreshSessionRecord | null>;
  findRefreshTokenHistory(tokenId: string): Promise<RefreshTokenHistoryRecord | null>;
  rotateRefreshSession(write: RefreshRotationWrite): Promise<boolean>;
  revokeRefreshFamily(sid: string, tokenId: string, occurredAt: string): Promise<void>;
  revokeRefreshSession(sid: string, reason: string, revokedAt: string): Promise<void>;
  revokeRefreshSessionsForAccount(accountId: number, reason: string, revokedAt: string): Promise<void>;
  cleanupRefreshData(now: string, batchSize: number): Promise<void>;
}
