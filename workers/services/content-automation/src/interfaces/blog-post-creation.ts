import type { BlogPostRecord } from "./blog-public.js";

export interface CreatedBlogPost extends BlogPostRecord {
  requestHash: string;
}

export interface BlogPostCreationInput {
  userId: number;
  idempotencyKey: string;
  requestHash: string;
  title: string;
  summary: string;
  contentInline: string | null;
  contentObjectKey: string | null;
  categoryId: string;
  coverImage: string | null;
  tags: string[];
  publish: boolean;
  readTime: string;
  publishedAt: string | null;
  now: string;
}

export interface BlogPostCreationRepository {
  categoryExists(categoryId: string): Promise<boolean>;
  findCreateReceipt(userId: number, idempotencyKey: string): Promise<CreatedBlogPost | null>;
  createPost(input: BlogPostCreationInput): Promise<CreatedBlogPost>;
}

export interface ArticleContentStorage {
  read(objectKey: string): Promise<string>;
  write(objectKey: string, content: string): Promise<void>;
  delete(objectKey: string): Promise<void>;
}
