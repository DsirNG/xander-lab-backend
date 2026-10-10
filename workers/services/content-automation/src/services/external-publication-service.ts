import { ArticleContentTooLargeError } from "../interfaces/external-publication.js";
import type {
  BlogSyncResult,
  ContentPublicationRepository,
  ExternalPublicationPlatform,
  ArticleContentReader,
  PlatformPublicationPort,
} from "../interfaces/external-publication.js";
import { PlatformPublicationError } from "../mappers/platform-integration-publication-mapper.js";

export class ExternalPublicationError extends Error {
  constructor(readonly status: number, message: string, readonly code: string) {
    super(message);
    this.name = "ExternalPublicationError";
  }
}

export class ExternalPublicationService {
  constructor(
    private readonly repository: ContentPublicationRepository,
    private readonly contentReader: ArticleContentReader,
    private readonly platform: PlatformPublicationPort,
    private readonly now: () => Date = () => new Date(),
  ) {}

  async findSucceeded(userId: number, postId: number, platform: ExternalPublicationPlatform): Promise<BlogSyncResult | null> {
    this.validate(userId, postId, platform);
    return this.repository.findSucceeded(userId, postId, platform);
  }

  async sync(
    userId: number,
    postId: number,
    platform: ExternalPublicationPlatform,
    juejinOptions: { categoryId?: string | null; tagIds?: string[] | null } = {},
  ): Promise<BlogSyncResult> {
    this.validate(userId, postId, platform);
    const existing = await this.repository.findSucceeded(userId, postId, platform);
    if (existing) return existing;

    const post = await this.repository.findOwnedPost(userId, postId);
    if (!post) throw new ExternalPublicationError(404, "文章不存在", "POST_NOT_FOUND");

    const now = this.now();
    const nowText = now.toISOString();
    if (platform === "csdn") {
      const startOfDay = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate())).toISOString();
      const startOfNextDay = new Date(Date.parse(startOfDay) + 24 * 60 * 60_000).toISOString();
      if (await this.repository.countCsdnPublishedSince(userId, startOfDay, startOfNextDay) >= 5) {
        throw new ExternalPublicationError(429, "已达到 CSDN 每日同步上限", "CSDN_DAILY_LIMIT");
      }
      const cooldownUntil = new Date(now.getTime() + 30_000).toISOString();
      if (!await this.repository.claimCsdnCooldown(userId, nowText, cooldownUntil)) {
        throw new ExternalPublicationError(429, "CSDN 同步过于频繁，请稍后再试", "CSDN_COOLDOWN");
      }
    }

    const attemptToken = crypto.randomUUID();
    const leaseUntil = new Date(now.getTime() + 2 * 60_000).toISOString();
    const claimed = await this.repository.claimAttempt({
      userId, postId, platform, attemptToken, now: nowText, leaseUntil,
    });
    if (!claimed) {
      const completed = await this.repository.findSucceeded(userId, postId, platform);
      if (completed) return completed;
      throw new ExternalPublicationError(409, "站外发布正在处理中", "PUBLICATION_IN_PROGRESS");
    }

    try {
      const content = post.contentInline ?? (post.contentObjectKey
        ? await this.contentReader.read(post.contentObjectKey)
        : null);
      if (content === null) throw new ExternalPublicationError(422, "文章正文不可用", "POST_CONTENT_MISSING");
      const result = await this.platform.publish({
        userId,
        postId,
        platform,
        ...(platform === "juejin" ? {
          juejinCategoryId: juejinOptions.categoryId,
          juejinTagIds: juejinOptions.tagIds,
        } : {}),
        post: {
          id: post.id,
          title: post.title,
          summary: post.summary,
          content,
          category: post.category,
          categoryName: post.categoryName,
          coverImage: post.coverImage,
          tags: post.tags,
        },
      });
      if (result.platform !== platform || !result.externalId.trim()) {
        throw new ExternalPublicationError(502, "站外同步服务返回了无效结果", "INVALID_PLATFORM_RESULT");
      }
      const saved = await this.repository.markSucceeded({
        userId, postId, platform, attemptToken, result, publishedAt: this.now().toISOString(),
      });
      if (!saved) {
        const completed = await this.repository.findSucceeded(userId, postId, platform);
        if (completed) return completed;
        throw new ExternalPublicationError(409, "站外发布记录已被其他请求接管", "PUBLICATION_ATTEMPT_EXPIRED");
      }
      return result;
    } catch (error) {
      const typed = error instanceof ExternalPublicationError
        ? error
        : error instanceof ArticleContentTooLargeError
          ? new ExternalPublicationError(413, "文章正文超过 Worker 传输限制", "POST_CONTENT_TOO_LARGE")
        : error instanceof PlatformPublicationError
          ? new ExternalPublicationError(error.status, error.message, "PLATFORM_PUBLICATION_FAILED")
          : new ExternalPublicationError(503, "站外同步暂时不可用", "PUBLICATION_FAILED");
      try {
        await this.repository.markFailed({
          userId,
          postId,
          platform,
          attemptToken,
          errorCode: typed.code,
          errorMessage: typed.message,
          updatedAt: this.now().toISOString(),
        });
      } catch {
        // Keep the original failure; an expired lease must not mask the platform error.
      }
      throw typed;
    }
  }

  private validate(userId: number, postId: number, platform: string): asserts platform is ExternalPublicationPlatform {
    if (!Number.isSafeInteger(userId) || userId < 1 || !Number.isSafeInteger(postId) || postId < 1
      || (platform !== "csdn" && platform !== "juejin")) {
      throw new ExternalPublicationError(400, "站外同步请求参数非法", "INVALID_PUBLICATION_REQUEST");
    }
  }
}
