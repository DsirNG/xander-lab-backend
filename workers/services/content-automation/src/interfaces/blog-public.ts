export interface BlogPostRecord {
  id: number;
  title: string;
  summary: string;
  category: string;
  categoryName: string | null;
  coverImage: string | null;
  userId: number | null;
  author: string;
  date: string | null;
  readTime: string;
  tips: string | null;
  views: number;
  status: number;
  csdnSynced: number;
  juejinSynced: number;
  tags: string[];
  contentInline?: string | null;
  contentObjectKey?: string | null;
}

export interface BlogPostPage {
  records: BlogPostRecord[];
  total: number;
  current: number;
  pages: number;
  size: number;
  hasMore: boolean;
}

export interface BlogCategoryRecord {
  id: string;
  name: string;
  count: number;
}

export interface BlogTagRecord {
  name: string;
  count: number;
}

export interface BlogViewInput {
  postId: number;
  ipAddress: string | null;
  userAgent: string | null;
  dedupeKey: string;
  eventId: string;
  now: string;
  expiresAt: string;
}

export interface BlogPublicRepository {
  listPublished(input: {
    page: number;
    size: number;
    category: string;
    tag: string;
    search: string;
  }): Promise<BlogPostPage>;
  recentPublished(limit: number): Promise<BlogPostRecord[]>;
  findPublishedPost(postId: number): Promise<BlogPostRecord | null>;
  recordPublicView(input: BlogViewInput): Promise<boolean>;
  countManagedPosts(userId: number): Promise<number>;
  listCategories(): Promise<BlogCategoryRecord[]>;
  listTags(limit?: number): Promise<BlogTagRecord[]>;
  cleanupExpiredViewDedup(now: string): Promise<void>;
}
