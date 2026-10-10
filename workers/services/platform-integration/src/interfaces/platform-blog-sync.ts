export interface PlatformBlogSyncPort {
  sync(input: {
    userId: number;
    postId: number;
    platform: "csdn" | "juejin";
    juejinCategoryId?: string | null;
    juejinTagIds?: string[] | null;
  }): Promise<{ platform: "csdn" | "juejin"; externalId: string; url: string | null }>;
}

export class PlatformBlogSyncError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
    this.name = "PlatformBlogSyncError";
  }
}
