export interface PlatformMcpBlogPort {
  list(userId: number, page: number, size: number, search: string): Promise<unknown>;
  get(userId: number, postId: number): Promise<unknown>;
  create(input: {
    userId: number; idempotencyKey: string; title: string; content: string; summary: string | null;
    categoryId: string; tags: string[] | null; publish: boolean;
  }): Promise<unknown>;
  update(userId: number, postId: number, input: Record<string, unknown>): Promise<unknown>;
  delete(userId: number, postId: number): Promise<void>;
}

export interface PlatformMcpPublicationPort {
  sync(input: {
    userId: number; postId: number; platform: "csdn" | "juejin";
    categoryId?: string | null; tagIds?: string[] | null;
  }): Promise<{ platform: "csdn" | "juejin"; externalId: string; url: string | null }>;
}

export interface PlatformMcpBillingPort {
  flatPoints(bizType: "csdn-publish" | "juejin-publish"): Promise<number>;
  charge(input: { userId: number; bizType: string; bizKey: string; bizRef: string; points: number; remark: string }): Promise<void>;
}

export class PlatformMcpPortError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
    this.name = "PlatformMcpPortError";
  }
}
