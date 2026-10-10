import type { D1Binding } from "@dinqor/worker-runtime";
import type {
  ExpiredPreviewSession,
  PreviewRepository,
  PreviewSession,
} from "../interfaces/preview-repository.js";

interface RateLimitRow {
  request_count: number;
}

/** Contains all D1 access for BlogHTML Preview. */
export class D1PreviewMapper implements PreviewRepository {
  constructor(private readonly db: D1Binding) {}

  async incrementRateLimit(fingerprint: string, windowStartedAt: number, now: number): Promise<number> {
    const row = await this.db.prepare(`
      INSERT INTO preview_rate_limit (
        ip_fingerprint, window_started_at, request_count, updated_at
      ) VALUES (?, ?, 1, ?)
      ON CONFLICT(ip_fingerprint) DO UPDATE SET
        request_count = CASE
          WHEN preview_rate_limit.window_started_at < excluded.window_started_at THEN 1
          ELSE preview_rate_limit.request_count + 1
        END,
        window_started_at = CASE
          WHEN preview_rate_limit.window_started_at < excluded.window_started_at THEN excluded.window_started_at
          ELSE preview_rate_limit.window_started_at
        END,
        updated_at = excluded.updated_at
      RETURNING request_count
    `).bind(fingerprint, windowStartedAt, now).first<RateLimitRow>();
    return row?.request_count ?? Number.MAX_SAFE_INTEGER;
  }

  async createSession(session: PreviewSession): Promise<void> {
    await this.db.prepare(`
      INSERT INTO preview_session (
        session_id, object_key, inline_code, code_size, content_sha256, created_at, expires_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?)
    `).bind(
      session.sessionId,
      session.objectKey,
      session.inlineCode,
      session.codeSize,
      session.contentSha256,
      session.createdAt,
      session.expiresAt,
    ).run();
  }

  findActiveSession(sessionId: string, now: number): Promise<PreviewSession | null> {
    return this.db.prepare(`
      SELECT session_id AS sessionId, object_key AS objectKey, inline_code AS inlineCode,
             code_size AS codeSize, content_sha256 AS contentSha256,
             created_at AS createdAt, expires_at AS expiresAt
      FROM preview_session
      WHERE session_id = ? AND expires_at > ?
    `).bind(sessionId, now).first<PreviewSession>();
  }

  async listExpiredSessions(now: number, limit: number): Promise<ExpiredPreviewSession[]> {
    const rows = await this.db.prepare(`
      SELECT session_id AS sessionId, object_key AS objectKey
      FROM preview_session
      WHERE expires_at <= ?
      ORDER BY expires_at ASC
      LIMIT ?
    `).bind(now, limit).all<ExpiredPreviewSession>();
    return rows.results;
  }

  async deleteExpiredSession(sessionId: string, now: number): Promise<void> {
    await this.db.prepare(
      "DELETE FROM preview_session WHERE session_id = ? AND expires_at <= ?",
    ).bind(sessionId, now).run();
  }

  async deleteOldRateLimits(before: number): Promise<void> {
    await this.db.prepare(
      "DELETE FROM preview_rate_limit WHERE window_started_at < ?",
    ).bind(before).run();
  }
}
