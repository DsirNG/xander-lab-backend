export type NotificationBroadcastResult =
  | { kind: "sent"; recipients: number }
  | { kind: "unconfigured" | "unavailable" | "invalid_response" };

export interface ContentNotificationBroadcastRepository {
  testBroadcast(): Promise<NotificationBroadcastResult>;
}
