import type { BlogPostRecord } from "./blog-public.js";

export interface ManagedBlogPost extends Omit<BlogPostRecord, "csdnSynced" | "juejinSynced"> {
  csdnSynced: boolean;
  juejinSynced: boolean;
  contentObjectKey: string | null;
  publishedAt: string | null;
}

export interface BlogManagementPage {
  records: ManagedBlogPost[];
  total: number;
  current: number;
  size: number;
  pages: number;
  hasMore: boolean;
}

export interface BlogPublishRhythm {
  weekStart: string;
  dailyAverage: number;
  days: Array<{ date: string; count: number }>;
}

export interface BlogManagementStats {
  totalArticles: number;
  drafts: number;
  published: number;
  trashed: number;
  recentViews: number;
  recentPublished: number;
  averageViews: number;
  dailyViews: Array<{ date: string; views: number }>;
}

export interface BlogManagementRepository {
  categoryExists(categoryId: string): Promise<boolean>;
  findById(postId: number): Promise<ManagedBlogPost | null>;
  findMine(userId: number, postId: number): Promise<ManagedBlogPost | null>;
  listMine(userId: number, status: number | null, search: string, page: number, size: number): Promise<BlogManagementPage>;
  update(userId: number, postId: number, patch: Record<string, string | number | null>, tags?: string[]): Promise<boolean>;
  updateStatus(userId: number, postId: number, status: number, publishedAt: string | null): Promise<boolean>;
  moveToTrash(userId: number, postId: number): Promise<boolean>;
  permanentlyDelete(userId: number, postId: number): Promise<boolean>;
  publishedPostId(userId: number, requestId: string): Promise<number | null>;
  publishRhythm(userId: number, today: string): Promise<BlogPublishRhythm>;
  manageStats(userId: number, today: string): Promise<BlogManagementStats>;
}
