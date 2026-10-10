import type { ManagedBlogCountRepository, ManagedBlogCountResult } from "../interfaces/managed-blog-count.js";

export interface ContentManagedBlogCountEnvironment {
  CONTENT_AUTOMATION_SERVICE_URL?: string;
  CONTENT_AUTOMATION_SERVICE_TOKEN?: string;
}

export class ContentManagedBlogCountMapper implements ManagedBlogCountRepository {
  constructor(private readonly env: ContentManagedBlogCountEnvironment) {}

  async countForUser(userId: number): Promise<ManagedBlogCountResult> {
    if (!this.env.CONTENT_AUTOMATION_SERVICE_URL || !this.env.CONTENT_AUTOMATION_SERVICE_TOKEN) {
      return { kind: "unconfigured" };
    }
    try {
      const endpoint = new URL(
        "/internal/content/profile/managed-count",
        this.env.CONTENT_AUTOMATION_SERVICE_URL,
      );
      const response = await fetch(endpoint, {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "x-dinqor-service-token": this.env.CONTENT_AUTOMATION_SERVICE_TOKEN,
        },
        body: JSON.stringify({ userId }),
        signal: AbortSignal.timeout(5_000),
      });
      if (!response.ok) return { kind: "unavailable" };
      const payload: unknown = await response.json();
      if (!payload || typeof payload !== "object" || Array.isArray(payload)) {
        return { kind: "invalid_response" };
      }
      const count = (payload as Record<string, unknown>).count;
      return typeof count === "number" && Number.isSafeInteger(count) && count >= 0
        ? { kind: "count", count }
        : { kind: "invalid_response" };
    } catch {
      return { kind: "unavailable" };
    }
  }
}
