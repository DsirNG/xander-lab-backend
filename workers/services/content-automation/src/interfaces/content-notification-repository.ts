export interface ContentNotification {
  id: number;
  type: string;
  title: string;
  message: string;
  planId: number | null;
  runId: number | null;
  resourceType: string | null;
  resourceId: number | null;
  isRead: boolean;
  createdAt: string;
}

export interface ContentNotificationRepository {
  list(userId: number, limit: number, offset: number): Promise<ContentNotification[]>;
  listAfter(userId: number, afterId: number, limit: number): Promise<ContentNotification[]>;
  count(userId: number): Promise<number>;
  unreadCount(userId: number): Promise<number>;
  markRead(userId: number, id: number): Promise<boolean>;
  markAllRead(userId: number): Promise<void>;
  create(input: {
    userId: number;
    type: string;
    title: string;
    message: string;
    planId?: number | null;
    runId?: number | null;
    resourceType?: string | null;
    resourceId?: number | null;
  }): Promise<number>;
  openStream(connectionId: string, ownerId: number, nowMs: number): Promise<NotificationStreamPosition>;
  heartbeatStream(connectionId: string, nowMs: number): Promise<void>;
  closeStream(connectionId: string): Promise<void>;
  listBroadcastsAfter(afterId: number, nowMs: number, limit: number): Promise<NotificationBroadcast[]>;
  broadcastTest(nowMs: number, expiresAtMs: number): Promise<number>;
  cleanupExpiredStreams(nowMs: number): Promise<void>;
}

export interface NotificationStreamPosition {
  lastNotificationId: number;
  lastBroadcastId: number;
}

export interface NotificationBroadcast {
  id: number;
  type: string;
  title: string;
  message: string;
}
