import type {
  ContentNotificationRepository,
  NotificationStreamPosition,
} from "../interfaces/content-notification-repository.js";

export class ContentNotificationService {
  constructor(private readonly repository: ContentNotificationRepository) {}

  async list(userId: number, page: number, size: number) {
    const safePage = Math.max(1, Math.min(page, 2_147_483_647));
    const safeSize = Math.max(1, Math.min(size, 20));
    const [records, total, unreadCount] = await Promise.all([
      this.repository.list(userId, safeSize, (safePage - 1) * safeSize),
      this.repository.count(userId),
      this.repository.unreadCount(userId),
    ]);
    return { records, total, unreadCount };
  }

  unreadCount(userId: number): Promise<number> {
    return this.repository.unreadCount(userId);
  }

  markRead(userId: number, id: number): Promise<boolean> {
    return this.repository.markRead(userId, id);
  }

  markAllRead(userId: number): Promise<void> {
    return this.repository.markAllRead(userId);
  }

  listAfter(userId: number, afterId: number) {
    return this.repository.listAfter(userId, afterId, 50);
  }

  create(input: Parameters<ContentNotificationRepository["create"]>[0]) {
    return this.repository.create(input);
  }

  openStream(connectionId: string, ownerId: number, nowMs: number): Promise<NotificationStreamPosition> {
    return this.repository.openStream(connectionId, ownerId, nowMs);
  }

  heartbeatStream(connectionId: string, nowMs: number): Promise<void> {
    return this.repository.heartbeatStream(connectionId, nowMs);
  }

  closeStream(connectionId: string): Promise<void> {
    return this.repository.closeStream(connectionId);
  }

  listBroadcastsAfter(afterId: number, nowMs: number) {
    return this.repository.listBroadcastsAfter(afterId, nowMs, 50);
  }

  broadcastTest(nowMs = Date.now()): Promise<number> {
    return this.repository.broadcastTest(nowMs, nowMs + 120_000);
  }

  cleanupExpiredStreams(nowMs = Date.now()): Promise<void> {
    return this.repository.cleanupExpiredStreams(nowMs);
  }
}
