import type {
  ContentNotificationBroadcastRepository,
  NotificationBroadcastResult,
} from "../interfaces/content-notification-broadcast-repository.js";

export class NotificationDiagnosticService {
  constructor(private readonly notifications: ContentNotificationBroadcastRepository) {}

  testBroadcast(): Promise<NotificationBroadcastResult> {
    return this.notifications.testBroadcast();
  }
}
