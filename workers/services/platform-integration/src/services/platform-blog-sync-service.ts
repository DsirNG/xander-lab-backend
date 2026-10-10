import { PlatformBlogSyncError, type PlatformBlogSyncPort } from "../interfaces/platform-blog-sync.js";

export class PlatformBlogSyncService {
  constructor(private readonly publication: PlatformBlogSyncPort) {}

  sync(input: {
    userId: number;
    postId: number;
    platform: "csdn" | "juejin";
    juejinCategoryId?: string | null;
    juejinTagIds?: string[] | null;
  }) {
    if (!Number.isSafeInteger(input.userId) || input.userId < 1
      || !Number.isSafeInteger(input.postId) || input.postId < 1
      || (input.platform !== "csdn" && input.platform !== "juejin")) {
      throw new PlatformBlogSyncError(400, "站外同步请求参数非法");
    }
    if (input.platform === "juejin") {
      if (input.juejinCategoryId !== undefined && input.juejinCategoryId !== null
        && (typeof input.juejinCategoryId !== "string" || input.juejinCategoryId.length > 200)) {
        throw new PlatformBlogSyncError(400, "掘金分类参数非法");
      }
      if (input.juejinTagIds !== undefined && input.juejinTagIds !== null
        && (!Array.isArray(input.juejinTagIds) || input.juejinTagIds.length > 50
          || input.juejinTagIds.some((tag) => typeof tag !== "string" || tag.length > 200))) {
        throw new PlatformBlogSyncError(400, "掘金标签参数非法");
      }
    }
    return this.publication.sync(input);
  }
}
