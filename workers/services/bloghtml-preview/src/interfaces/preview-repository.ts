export interface PreviewSession {
  sessionId: string;
  objectKey: string | null;
  inlineCode: string | null;
  codeSize: number;
  contentSha256: string;
  createdAt: number;
  expiresAt: number;
}

export interface ExpiredPreviewSession {
  sessionId: string;
  objectKey: string | null;
}

/** D1-owned preview session and fixed-window rate-limit state. */
export interface PreviewRepository {
  incrementRateLimit(fingerprint: string, windowStartedAt: number, now: number): Promise<number>;
  createSession(session: PreviewSession): Promise<void>;
  findActiveSession(sessionId: string, now: number): Promise<PreviewSession | null>;
  listExpiredSessions(now: number, limit: number): Promise<ExpiredPreviewSession[]>;
  deleteExpiredSession(sessionId: string, now: number): Promise<void>;
  deleteOldRateLimits(before: number): Promise<void>;
}
