import type { ArticleContentReader } from "../interfaces/external-publication.js";
import type {
  BlogPostRecord,
  BlogPublicRepository,
} from "../interfaces/blog-public.js";

export interface BlogPostResponse {
  id: number;
  title: string;
  summary: string;
  content: string | null;
  category: string;
  categoryName: string | null;
  coverImage: string | null;
  tags: string[];
  userId: number | null;
  author: string;
  date: string | null;
  readTime: string;
  tips: string | null;
  views: number;
  status: number;
  csdnSynced: boolean;
  juejinSynced: boolean;
}

function toResponse(row: BlogPostRecord, content: string | null = null): BlogPostResponse {
  return {
    id: row.id,
    title: row.title,
    summary: row.summary,
    content,
    category: row.category,
    categoryName: row.categoryName,
    coverImage: row.coverImage,
    tags: row.tags,
    userId: row.userId,
    author: row.author,
    date: row.date,
    readTime: row.readTime,
    tips: row.tips,
    views: row.views,
    status: row.status,
    csdnSynced: Boolean(row.csdnSynced),
    juejinSynced: Boolean(row.juejinSynced),
  };
}

export class BlogPublicService {
  constructor(
    private readonly repository: BlogPublicRepository,
    private readonly contentReader: ArticleContentReader,
    private readonly now: () => Date = () => new Date(),
  ) {}

  async listPublished(input: {
    page: number;
    size: number;
    category: string;
    tag: string;
    search: string;
  }) {
    const page = Math.max(input.page, 1);
    const size = Math.min(Math.max(input.size, 1), 50);
    const result = await this.repository.listPublished({ ...input, page, size });
    return { ...result, records: result.records.map((row) => toResponse(row)) };
  }

  async recentPublished(limit: number): Promise<BlogPostResponse[]> {
    const safeLimit = Math.min(Math.max(limit, 1), 50);
    return (await this.repository.recentPublished(safeLimit)).map((row) => toResponse(row));
  }

  async findPublishedPost(postId: number): Promise<BlogPostResponse | null> {
    const row = await this.repository.findPublishedPost(postId);
    if (!row) return null;
    const content = row.contentInline ?? (row.contentObjectKey
      ? await this.contentReader.read(row.contentObjectKey)
      : null);
    return toResponse(row, content);
  }

  async recordPublicView(input: {
    postId: number;
    ipAddress: string | null;
    userAgent: string | null;
  }): Promise<{ counted: boolean }> {
    const now = this.now();
    const nowText = now.toISOString();
    const expiresAt = new Date(now.getTime() + 24 * 60 * 60 * 1000).toISOString();
    const identity = input.ipAddress ?? "unknown";
    const digest = await crypto.subtle.digest(
      "SHA-256",
      new TextEncoder().encode(`blog-view:${input.postId}:ip:${identity}`),
    );
    const dedupeKey = Array.from(new Uint8Array(digest), (byte) =>
      byte.toString(16).padStart(2, "0")).join("");
    const counted = await this.repository.recordPublicView({
      ...input,
      dedupeKey,
      eventId: crypto.randomUUID(),
      now: nowText,
      expiresAt,
    });
    return { counted };
  }

  countManagedPosts(userId: number): Promise<number> {
    return this.repository.countManagedPosts(userId);
  }

  listCategories() {
    return this.repository.listCategories();
  }

  listTags(limit?: number) {
    return this.repository.listTags(limit);
  }

  cleanupExpiredViewDedup(now: string): Promise<void> {
    return this.repository.cleanupExpiredViewDedup(now);
  }
}
