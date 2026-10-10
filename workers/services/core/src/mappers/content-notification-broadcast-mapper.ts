import type {
  ContentNotificationBroadcastRepository,
  NotificationBroadcastResult,
} from "../interfaces/content-notification-broadcast-repository.js";
import { fetchWithServiceBinding, type WorkerServiceBinding } from "@dinqor/worker-runtime";

export interface ContentNotificationBroadcastEnvironment {
  CONTENT_AUTOMATION_SERVICE_URL?: string;
  CONTENT_AUTOMATION_SERVICE_TOKEN?: string;
  CONTENT_AUTOMATION_SERVICE?: WorkerServiceBinding;
}

export class ContentNotificationBroadcastMapper implements ContentNotificationBroadcastRepository {
  constructor(private readonly env: ContentNotificationBroadcastEnvironment) {}

  async testBroadcast(): Promise<NotificationBroadcastResult> {
    if (!this.env.CONTENT_AUTOMATION_SERVICE_URL || !this.env.CONTENT_AUTOMATION_SERVICE_TOKEN) {
      return { kind: "unconfigured" };
    }
    try {
      const endpoint = new URL(
        "/internal/content/notifications/test-broadcast",
        this.env.CONTENT_AUTOMATION_SERVICE_URL,
      );
      const response = await fetchWithServiceBinding(this.env.CONTENT_AUTOMATION_SERVICE, endpoint, {
        method: "POST",
        headers: { "x-dinqor-service-token": this.env.CONTENT_AUTOMATION_SERVICE_TOKEN },
        signal: AbortSignal.timeout(5_000),
      });
      if (!response.ok) return { kind: "unavailable" };
      const payload: unknown = await response.json();
      if (!payload || typeof payload !== "object" || Array.isArray(payload)) {
        return { kind: "invalid_response" };
      }
      const recipients = (payload as Record<string, unknown>).recipients;
      return typeof recipients === "number" && Number.isSafeInteger(recipients) && recipients >= 0
        ? { kind: "sent", recipients }
        : { kind: "invalid_response" };
    } catch {
      return { kind: "unavailable" };
    }
  }
}
