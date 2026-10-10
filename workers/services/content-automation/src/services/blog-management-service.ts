import type { ArticleContentStorage } from "../interfaces/blog-post-creation.js";
import type { BlogManagementRepository, ManagedBlogPost } from "../interfaces/blog-management-repository.js";

const INLINE_CONTENT_LIMIT_BYTES = 256 * 1024;
const MAX_CONTENT_BYTES = 2 * 1024 * 1024;
const MAX_TAGS = 8;

export class BlogManagementError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
    this.name = "BlogManagementError";
  }
}

export class BlogManagementService {
  constructor(
    private readonly repository: BlogManagementRepository,
    private readonly contentStorage: ArticleContentStorage,
    private readonly now: () => Date = () => new Date(),
  ) {}

  async listMine(userId: number, status: number | null, search: string, page: number, size: number) {
    const normalizedStatus = this.normalizeFilterStatus(status);
    const safePage = Math.max(1, page);
    const safeSize = Math.min(Math.max(1, size), 50);
    const result = await this.repository.listMine(userId, normalizedStatus, search, safePage, safeSize);
    return { ...result, records: await Promise.all(result.records.map((post) => this.toResponse(post))) };
  }

  async findMine(userId: number, postId: number) {
    const post = await this.repository.findMine(userId, postId);
    return post ? this.toResponse(post) : null;
  }

  async update(userId: number, postId: number, input: unknown) {
    if (!input || typeof input !== "object" || Array.isArray(input)) {
      throw new BlogManagementError(400, "请求体不能为空");
    }
    const existing = await this.requireOwned(userId, postId);
    const body = input as Record<string, unknown>;
    const patch: Record<string, string | number | null> = {};
    let tags: string[] | undefined;

    if (body.title !== undefined && body.title !== null) {
      if (typeof body.title !== "string" || !body.title.trim() || body.title.trim().length > 300) {
        throw new BlogManagementError(400, "文章标题格式不正确");
      }
      patch.title = body.title.trim();
    }
    if (body.summary !== undefined && body.summary !== null) {
      if (typeof body.summary !== "string" || body.summary.length > 2_000) {
        throw new BlogManagementError(400, "文章摘要格式不正确");
      }
      patch.summary = body.summary;
    }
    if (body.categoryId !== undefined && body.categoryId !== null) {
      if (typeof body.categoryId !== "string" || !body.categoryId.trim() || body.categoryId.length > 200) {
        throw new BlogManagementError(400, "文章分类格式不正确");
      }
      const categoryId = body.categoryId.trim();
      if (!await this.repository.categoryExists(categoryId)) {
        throw new BlogManagementError(400, "文章分类不存在");
      }
      patch.categoryId = categoryId;
    }
    if (body.coverImage !== undefined && body.coverImage !== null) {
      if (typeof body.coverImage !== "string" || body.coverImage.length > 2_000) {
        throw new BlogManagementError(400, "封面图片地址格式不正确");
      }
      patch.coverImage = body.coverImage.trim() || null;
    }
    if (body.tags !== undefined && body.tags !== null) {
      if (!Array.isArray(body.tags) || body.tags.some((tag) => typeof tag !== "string" || tag.length > 100)) {
        throw new BlogManagementError(400, "文章标签格式不正确");
      }
      tags = [...new Set((body.tags as string[]).map((tag) => tag.trim()).filter(Boolean))].slice(0, MAX_TAGS);
    }

    let newObjectKey: string | null = null;
    let oldObjectKey: string | null = null;
    if (body.content !== undefined && body.content !== null) {
      if (typeof body.content !== "string") throw new BlogManagementError(400, "文章正文格式不正确");
      const byteLength = new TextEncoder().encode(body.content).byteLength;
      if (byteLength > MAX_CONTENT_BYTES) throw new BlogManagementError(413, "文章正文不能超过 2 MiB");
      oldObjectKey = existing.contentObjectKey;
      if (byteLength > INLINE_CONTENT_LIMIT_BYTES) {
        newObjectKey = `posts/${userId}/${crypto.randomUUID()}.md`;
        await this.contentStorage.write(newObjectKey, body.content);
        patch.contentInline = null;
        patch.contentObjectKey = newObjectKey;
      } else {
        patch.contentInline = body.content;
        patch.contentObjectKey = null;
      }
      if (body.content.trim()) {
        patch.readTime = `${Math.max(1, Math.floor(body.content.length / 500))} min`;
      }
    }

    if (Object.keys(patch).length === 0 && tags === undefined) {
      throw new BlogManagementError(400, "至少提供一个需要更新的字段");
    }

    try {
      const updated = await this.repository.update(userId, postId, patch, tags);
      if (!updated) throw new BlogManagementError(404, "文章不存在");
      if (oldObjectKey && oldObjectKey !== newObjectKey) await this.deleteQuietly(oldObjectKey);
    } catch (error) {
      if (newObjectKey) await this.deleteQuietly(newObjectKey);
      throw error;
    }

    const result = await this.repository.findMine(userId, postId);
    if (!result) throw new BlogManagementError(404, "文章不存在");
    return this.toResponse(result);
  }

  async updateStatus(userId: number, postId: number, input: unknown) {
    if (!input || typeof input !== "object" || Array.isArray(input)) {
      throw new BlogManagementError(400, "状态不能为空");
    }
    const status = (input as Record<string, unknown>).status;
    if (status !== -1 && status !== 0 && status !== 1) {
      throw new BlogManagementError(400, "不支持的文章状态");
    }
    const existing = await this.requireOwned(userId, postId);
    const publishedAt = status === 1 && existing.publishedAt === null
      ? this.now().toISOString().slice(0, 10)
      : null;
    if (!await this.repository.updateStatus(userId, postId, status, publishedAt)) {
      throw new BlogManagementError(404, "文章不存在");
    }
    return this.findMine(userId, postId);
  }

  async moveToTrash(userId: number, postId: number): Promise<void> {
    await this.requireOwned(userId, postId);
    if (!await this.repository.moveToTrash(userId, postId)) throw new BlogManagementError(404, "文章不存在");
  }

  async permanentlyDelete(userId: number, postId: number): Promise<void> {
    const existing = await this.requireOwned(userId, postId);
    if (existing.status !== -1) throw new BlogManagementError(400, "仅回收站中的文章可以彻底删除");
    if (!await this.repository.permanentlyDelete(userId, postId)) throw new BlogManagementError(404, "文章不存在");
    if (existing.contentObjectKey) await this.deleteQuietly(existing.contentObjectKey);
  }

  publishedPostId(userId: number, requestId: string): Promise<number | null> {
    const normalized = requestId.trim();
    return normalized ? this.repository.publishedPostId(userId, normalized) : Promise.resolve(null);
  }

  publishRhythm(userId: number) {
    return this.repository.publishRhythm(userId, this.now().toISOString().slice(0, 10));
  }

  manageStats(userId: number) {
    return this.repository.manageStats(userId, this.now().toISOString().slice(0, 10));
  }

  private async requireOwned(userId: number, postId: number): Promise<ManagedBlogPost> {
    const post = await this.repository.findById(postId);
    if (!post) throw new BlogManagementError(404, "文章不存在");
    if (post.userId !== userId) throw new BlogManagementError(403, "无权操作该文章");
    return post;
  }

  private normalizeFilterStatus(status: number | null): number | null {
    if (status === null || status === 0 || status === 1 || status === -1) return status;
    throw new BlogManagementError(400, "状态参数必须是 0、1 或 -1");
  }

  private async toResponse(post: ManagedBlogPost) {
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
      csdnSynced: post.csdnSynced,
      juejinSynced: post.juejinSynced,
    };
  }

  private async deleteQuietly(objectKey: string): Promise<void> {
    try {
      await this.contentStorage.delete(objectKey);
    } catch {
      // OSS lifecycle cleanup removes objects left behind by a failed compensation.
    }
  }
}
