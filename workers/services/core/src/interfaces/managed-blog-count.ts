export type ManagedBlogCountResult =
  | { kind: "count"; count: number }
  | { kind: "unconfigured" }
  | { kind: "unavailable" }
  | { kind: "invalid_response" };

export interface ManagedBlogCountRepository {
  countForUser(userId: number): Promise<ManagedBlogCountResult>;
}
