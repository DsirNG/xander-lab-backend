import type { ArticleContentStorage, BlogPostCreationRepository, CreatedBlogPost } from "../interfaces/blog-post-creation.js";

const INLINE_CONTENT_LIMIT_BYTES = 256 * 1024;
const MAX_CONTENT_BYTES = 2 * 1024 * 1024;
const MAX_TAGS = 8;

export class BlogPostCreationError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
    this.name = "BlogPostCreationError";
  }
}

export interface BlogPostCreationRequest {
  userId: number;
  idempotencyKey?: string | null;
  title: string;
  summary?: string | null;
  content: string;
  categoryId: string;
  coverImage?: string | null;
  tags?: string[] | null;
  publish?: boolean | null;
}

export class BlogPostCreationService {
  constructor(
    private readonly repository: BlogPostCreationRepository,
    private readonly contentStorage: ArticleContentStorage,
    private readonly now: () => Date = () => new Date(),
  ) {}

  async create(input: BlogPostCreationRequest) {
    const normalized = this.normalize(input);
    if (!await this.repository.categoryExists(normalized.categoryId)) {
      throw new BlogPostCreationError(400, "文章分类不存在");
    }

    const idempotencyKey = normalized.idempotencyKey ?? crypto.randomUUID();
    const requestHash = await this.requestHash(normalized);
    const existing = await this.repository.findCreateReceipt(normalized.userId, idempotencyKey);
    if (existing) return this.replay(existing, requestHash);

    const contentBytes = new TextEncoder().encode(normalized.content).byteLength;
    const contentObjectKey = contentBytes > INLINE_CONTENT_LIMIT_BYTES
      ? `posts/${normalized.userId}/${crypto.randomUUID()}.md`
      : null;
    const now = this.now();
    const nowText = now.toISOString();
    const publishedAt = normalized.publish ? nowText.slice(0, 10) : null;
    let objectMayExist = contentObjectKey !== null;

    try {
      if (contentObjectKey) await this.contentStorage.write(contentObjectKey, normalized.content);
      const post = await this.repository.createPost({
        userId: normalized.userId,
        idempotencyKey,
        requestHash,
        title: normalized.title,
        summary: normalized.summary,
        contentInline: contentObjectKey ? null : normalized.content,
        contentObjectKey,
        categoryId: normalized.categoryId,
        coverImage: normalized.coverImage,
        tags: normalized.tags,
        publish: normalized.publish,
        readTime: `${Math.max(1, Math.floor(normalized.content.length / 500))} min`,
        publishedAt,
        now: nowText,
      });
      if (contentObjectKey && post.contentObjectKey !== contentObjectKey) {
        objectMayExist = false;
        await this.deleteQuietly(contentObjectKey);
      } else if (contentObjectKey) {
        objectMayExist = false;
      }
      return this.replay(post, requestHash);
    } catch (error) {
      if (objectMayExist && contentObjectKey) await this.deleteQuietly(contentObjectKey);
      throw error;
    }
  }

  private normalize(input: BlogPostCreationRequest) {
    if (!input || !Number.isSafeInteger(input.userId) || input.userId < 1
      || typeof input.title !== "string" || !input.title.trim() || input.title.trim().length > 300
      || typeof input.content !== "string" || !input.content.trim()
      || new TextEncoder().encode(input.content).byteLength > MAX_CONTENT_BYTES
      || (input.summary !== undefined && input.summary !== null
        && (typeof input.summary !== "string" || input.summary.length > 2_000))
      || typeof input.categoryId !== "string" || !input.categoryId.trim() || input.categoryId.length > 200
      || (input.coverImage !== undefined && input.coverImage !== null
        && (typeof input.coverImage !== "string" || input.coverImage.length > 2_000))
      || (input.tags !== undefined && input.tags !== null
        && (!Array.isArray(input.tags) || input.tags.some((tag) => typeof tag !== "string" || tag.length > 100)))
      || (input.publish !== undefined && input.publish !== null && typeof input.publish !== "boolean")) {
      throw new BlogPostCreationError(400, "文章内容格式不正确");
    }

    const title = input.title.trim();
    const content = input.content;
    const suppliedSummary = input.summary?.trim();
    const summary = suppliedSummary || (content.length <= 160 ? content : `${content.slice(0, 159)}…`);
    const categoryId = input.categoryId.trim();
    const coverImage = input.coverImage?.trim() || null;
    if (coverImage && !this.isHttpUrl(coverImage)) {
      throw new BlogPostCreationError(400, "封面图片地址格式不正确");
    }
    const tags = [...new Set((input.tags ?? []).map((tag) => tag.trim()).filter(Boolean))].slice(0, MAX_TAGS);
    const idempotencyKey = input.idempotencyKey?.trim() || null;
    if (idempotencyKey && (!/^[A-Za-z0-9._:-]{1,200}$/.test(idempotencyKey))) {
      throw new BlogPostCreationError(400, "幂等键格式不正确");
    }
    return {
      userId: input.userId,
      idempotencyKey,
      title,
      summary,
      content,
      categoryId,
      coverImage,
      tags,
      publish: input.publish !== false,
    };
  }

  private async requestHash(input: ReturnType<BlogPostCreationService["normalize"]>): Promise<string> {
    const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(JSON.stringify({
      title: input.title,
      summary: input.summary,
      content: input.content,
      categoryId: input.categoryId,
      coverImage: input.coverImage,
      tags: input.tags,
      publish: input.publish,
    })));
    return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
  }

  private async replay(post: CreatedBlogPost, requestHash: string) {
    if (post.requestHash !== requestHash) {
      throw new BlogPostCreationError(409, "该幂等键已用于其他文章内容");
    }
    const content = post.contentInline ?? (post.contentObjectKey
      ? await this.contentStorage.read(post.contentObjectKey)
      : null);
    return {
      id: post.id,
      title: post.title,
      summary: post.summary,
      content,
      category: post.category,
      categoryName: post.categoryName,
      coverImage: post.coverImage,
      tags: post.tags,
      userId: post.userId,
      author: post.author,
      date: post.date,
      readTime: post.readTime,
      tips: post.tips,
      views: post.views,
      status: post.status,
      csdnSynced: Boolean(post.csdnSynced),
      juejinSynced: Boolean(post.juejinSynced),
    };
  }

  private isHttpUrl(value: string): boolean {
    try {
      const url = new URL(value);
      return (url.protocol === "https:" || url.protocol === "http:") && !url.username && !url.password;
    } catch {
      return false;
    }
  }

  private async deleteQuietly(objectKey: string): Promise<void> {
    try {
      await this.contentStorage.delete(objectKey);
    } catch {
      // A failed compensation is cleaned by the configured OSS lifecycle rule.
    }
  }
}
