import type { D1Binding } from "@dinqor/worker-runtime";
import type {
  ContentNotification,
  ContentNotificationRepository,
  NotificationBroadcast,
} from "../interfaces/content-notification-repository.js";

interface NotificationRow {
  id: number;
  type: string;
  title: string;
  message: string;
  planId: number | null;
  runId: number | null;
  resourceType: string | null;
  resourceId: number | null;
  isRead: number;
  createdAt: string;
}

const SELECT_COLUMNS = `
  SELECT id, type, title, message, plan_id AS planId, run_id AS runId,
         resource_type AS resourceType, resource_id AS resourceId,
         is_read AS isRead, created_at AS createdAt
  FROM blog_notification
`;

function map(row: NotificationRow): ContentNotification {
  return { ...row, isRead: row.isRead !== 0 };
}

export class D1ContentNotificationMapper implements ContentNotificationRepository {
  constructor(private readonly db: D1Binding) {}

  async list(userId: number, limit: number, offset: number): Promise<ContentNotification[]> {
    const result = await this.db.prepare(
      `${SELECT_COLUMNS} WHERE user_id = ? ORDER BY id DESC LIMIT ? OFFSET ?`,
    ).bind(userId, limit, offset).all<NotificationRow>();
    return result.results.map(map);
  }

  async listAfter(userId: number, afterId: number, limit: number): Promise<ContentNotification[]> {
    const result = await this.db.prepare(
      `${SELECT_COLUMNS} WHERE user_id = ? AND id > ? ORDER BY id ASC LIMIT ?`,
    ).bind(userId, afterId, limit).all<NotificationRow>();
    return result.results.map(map);
  }

  async count(userId: number): Promise<number> {
    const row = await this.db.prepare(
      "SELECT COUNT(*) AS count FROM blog_notification WHERE user_id = ?",
    ).bind(userId).first<{ count: number }>();
    return row?.count ?? 0;
  }

  async unreadCount(userId: number): Promise<number> {
    const row = await this.db.prepare(
      "SELECT COUNT(*) AS count FROM blog_notification WHERE user_id = ? AND is_read = 0",
    ).bind(userId).first<{ count: number }>();
    return row?.count ?? 0;
  }

  async markRead(userId: number, id: number): Promise<boolean> {
    const changed = await this.db.prepare(
      "UPDATE blog_notification SET is_read = 1, updated_at = ? WHERE id = ? AND user_id = ? AND is_read = 0",
    ).bind(new Date().toISOString(), id, userId).run();
    if (changed.meta.changes > 0) return true;
    const existing = await this.db.prepare(
      "SELECT 1 AS found FROM blog_notification WHERE id = ? AND user_id = ?",
    ).bind(id, userId).first<{ found: number }>();
    return existing !== null;
  }

  async markAllRead(userId: number): Promise<void> {
    await this.db.prepare(
      "UPDATE blog_notification SET is_read = 1, updated_at = ? WHERE user_id = ? AND is_read = 0",
    ).bind(new Date().toISOString(), userId).run();
  }

  async create(input: {
    userId: number;
    type: string;
    title: string;
    message: string;
    planId?: number | null;
    runId?: number | null;
    resourceType?: string | null;
    resourceId?: number | null;
  }): Promise<number> {
    const result = await this.db.prepare(`
      INSERT INTO blog_notification
        (user_id, type, title, message, plan_id, run_id, resource_type, resource_id)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?)
      RETURNING id
    `).bind(
      input.userId, input.type, input.title, input.message,
      input.planId ?? null, input.runId ?? null,
      input.resourceType ?? null, input.resourceId ?? null,
    ).first<{ id: number }>();
    const id = result?.id;
    if (id === undefined || !Number.isSafeInteger(id) || id < 1) {
      throw new Error("Content notification could not be persisted");
    }
    return id;
  }

  async openStream(connectionId: string, ownerId: number, nowMs: number): Promise<number> {
    const row = await this.db.prepare(`
      INSERT INTO content_notification_stream
        (connection_id, owner_id, last_seen_at_ms, lease_until_ms, last_broadcast_id)
      VALUES (?, ?, ?, ?, (SELECT COALESCE(MAX(id), 0) FROM content_notification_broadcast))
      RETURNING last_broadcast_id AS lastBroadcastId
    `).bind(connectionId, ownerId, nowMs, nowMs + 45_000)
      .first<{ lastBroadcastId: number }>();
    if (!row || !Number.isSafeInteger(row.lastBroadcastId) || row.lastBroadcastId < 0) {
      throw new Error("Content notification stream could not be registered");
    }
    return row.lastBroadcastId;
  }

  async heartbeatStream(connectionId: string, nowMs: number): Promise<void> {
    await this.db.prepare(`
      UPDATE content_notification_stream
      SET last_seen_at_ms = ?, lease_until_ms = ?
      WHERE connection_id = ?
    `).bind(nowMs, nowMs + 45_000, connectionId).run();
  }

  async closeStream(connectionId: string): Promise<void> {
    await this.db.prepare("DELETE FROM content_notification_stream WHERE connection_id = ?")
      .bind(connectionId).run();
  }

  async listBroadcastsAfter(
    afterId: number,
    nowMs: number,
    limit: number,
  ): Promise<NotificationBroadcast[]> {
    const result = await this.db.prepare(`
      SELECT id, type, title, message FROM content_notification_broadcast
      WHERE id > ? AND expires_at_ms > ? ORDER BY id ASC LIMIT ?
    `).bind(afterId, nowMs, limit).all<NotificationBroadcast>();
    return result.results;
  }

  async broadcastTest(nowMs: number, expiresAtMs: number): Promise<number> {
    await this.cleanupExpiredStreams(nowMs);
    const active = await this.db.prepare(`
      SELECT COUNT(*) AS count FROM content_notification_stream WHERE lease_until_ms > ?
    `).bind(nowMs).first<{ count: number }>();
    const inserted = await this.db.prepare(`
      INSERT INTO content_notification_broadcast (type, title, message, created_at_ms, expires_at_ms)
      VALUES ('SSE_TEST_BROADCAST', 'SSE 广播测试', '管理员发起的实时通知测试', ?, ?)
      RETURNING id
    `).bind(nowMs, expiresAtMs).first<{ id: number }>();
    if (!inserted || !Number.isSafeInteger(inserted.id) || inserted.id < 1) {
      throw new Error("Content notification broadcast could not be persisted");
    }
    return active?.count ?? 0;
  }

  async cleanupExpiredStreams(nowMs: number): Promise<void> {
    await this.db.batch([
      this.db.prepare("DELETE FROM content_notification_stream WHERE lease_until_ms <= ?").bind(nowMs),
      this.db.prepare("DELETE FROM content_notification_broadcast WHERE expires_at_ms <= ?").bind(nowMs),
    ]);
  }
}
