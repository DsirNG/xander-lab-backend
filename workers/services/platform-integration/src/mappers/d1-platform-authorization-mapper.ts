import type { D1Binding } from "@dinqor/worker-runtime";
import type {
  PlatformAuthorizationQueueEntry,
  PlatformAuthorizationRepository,
  PlatformAuthorizationSlot,
} from "../interfaces/platform-authorization.js";
import type { PublishingPlatform } from "../interfaces/platform-credential-repository.js";

/** Persists one leased browser slot per provider and FIFO authorization requests in Platform D1. */
export class D1PlatformAuthorizationMapper implements PlatformAuthorizationRepository {
  constructor(private readonly db: D1Binding) {}

  findSlot(platform: PublishingPlatform): Promise<PlatformAuthorizationSlot | null> {
    return this.db.prepare(`
      SELECT platform, user_id AS userId, encrypted_session_id AS encryptedSessionId,
        starting_at AS startingAt, expires_at AS expiresAt
      FROM platform_authorization_slot WHERE platform = ?
    `).bind(platform).first<PlatformAuthorizationSlot>();
  }

  async tryAcquireSlot(platform: PublishingPlatform, userId: number, expiresAt: string): Promise<boolean> {
    const result = await this.db.prepare(`
      INSERT OR IGNORE INTO platform_authorization_slot (platform, user_id, expires_at)
      VALUES (?, ?, ?)
    `).bind(platform, userId, expiresAt).run();
    if (!result.success) throw new Error("platform authorization slot claim failed");
    return (result.meta?.changes ?? 0) === 1;
  }

  async tryMarkStarting(platform: PublishingPlatform, userId: number): Promise<boolean> {
    const result = await this.db.prepare(`
      UPDATE platform_authorization_slot SET starting_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now'),
        updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
      WHERE platform = ? AND user_id = ? AND encrypted_session_id IS NULL AND starting_at IS NULL
    `).bind(platform, userId).run();
    if (!result.success) throw new Error("platform authorization start claim failed");
    return (result.meta?.changes ?? 0) === 1;
  }

  async saveSessionId(platform: PublishingPlatform, userId: number, encryptedSessionId: string): Promise<boolean> {
    const result = await this.db.prepare(`
      UPDATE platform_authorization_slot SET encrypted_session_id = ?,
        updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
      WHERE platform = ? AND user_id = ? AND encrypted_session_id IS NULL AND starting_at IS NOT NULL
    `).bind(encryptedSessionId, platform, userId).run();
    if (!result.success) throw new Error("platform authorization session save failed");
    return (result.meta?.changes ?? 0) === 1;
  }

  async deleteSlot(platform: PublishingPlatform, userId: number): Promise<void> {
    const result = await this.db.prepare(
      "DELETE FROM platform_authorization_slot WHERE platform = ? AND user_id = ?",
    ).bind(platform, userId).run();
    if (!result.success) throw new Error("platform authorization slot release failed");
  }

  async enqueue(platform: PublishingPlatform, userId: number, expiresAt: string): Promise<void> {
    const result = await this.db.prepare(`
      INSERT INTO platform_authorization_queue (platform, user_id, expires_at)
      VALUES (?, ?, ?)
      ON CONFLICT(platform, user_id) DO UPDATE SET expires_at = excluded.expires_at
    `).bind(platform, userId, expiresAt).run();
    if (!result.success) throw new Error("platform authorization queue write failed");
  }

  async findQueueEntry(platform: PublishingPlatform, userId: number): Promise<PlatformAuthorizationQueueEntry | null> {
    return this.db.prepare(`
      SELECT platform, user_id AS userId,
        1 + (SELECT COUNT(*) FROM platform_authorization_queue earlier
          WHERE earlier.platform = current.platform AND earlier.expires_at > strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
            AND earlier.id < current.id) AS queuePosition,
        CAST(MAX(0, COALESCE((SELECT (julianday(slot.expires_at) - julianday('now')) * 86400
          FROM platform_authorization_slot slot WHERE slot.platform = current.platform), 0))
          + (SELECT COUNT(*) FROM platform_authorization_queue earlier
            WHERE earlier.platform = current.platform AND earlier.expires_at > strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
              AND earlier.id < current.id) * 300 AS INTEGER) AS estimatedWaitSeconds
      FROM platform_authorization_queue current
      WHERE current.platform = ? AND current.user_id = ?
        AND current.expires_at > strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
    `).bind(platform, userId).first<PlatformAuthorizationQueueEntry>();
  }

  async claimNext(platform: PublishingPlatform, expiresAt: string): Promise<number | null> {
    const result = await this.db.prepare(`
      INSERT OR IGNORE INTO platform_authorization_slot (platform, user_id, expires_at)
      SELECT ?, queued.user_id, ? FROM platform_authorization_queue queued
      WHERE queued.platform = ? AND queued.expires_at > strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
        AND queued.id = (SELECT MIN(id) FROM platform_authorization_queue
          WHERE platform = ? AND expires_at > strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
        AND NOT EXISTS (SELECT 1 FROM platform_authorization_slot WHERE platform = ?)
    `).bind(platform, expiresAt, platform, platform, platform).run();
    if (!result.success) throw new Error("platform authorization queue claim failed");
    if ((result.meta?.changes ?? 0) === 0) return null;
    const row = await this.findSlot(platform);
    if (!row) throw new Error("claimed platform authorization slot disappeared");
    await this.deleteQueueEntry(platform, row.userId);
    return row.userId;
  }

  async deleteQueueEntry(platform: PublishingPlatform, userId: number): Promise<void> {
    const result = await this.db.prepare(
      "DELETE FROM platform_authorization_queue WHERE platform = ? AND user_id = ?",
    ).bind(platform, userId).run();
    if (!result.success) throw new Error("platform authorization queue deletion failed");
  }

  async removeExpired(now: string): Promise<PlatformAuthorizationSlot[]> {
    const expired = await this.db.prepare(`
      SELECT platform, user_id AS userId, encrypted_session_id AS encryptedSessionId,
        starting_at AS startingAt, expires_at AS expiresAt
      FROM platform_authorization_slot WHERE expires_at <= ?
    `).bind(now).all<PlatformAuthorizationSlot>();
    await this.db.batch([
      this.db.prepare("DELETE FROM platform_authorization_slot WHERE expires_at <= ?").bind(now),
      this.db.prepare("DELETE FROM platform_authorization_queue WHERE expires_at <= ?").bind(now),
    ]);
    return expired.results;
  }
}
