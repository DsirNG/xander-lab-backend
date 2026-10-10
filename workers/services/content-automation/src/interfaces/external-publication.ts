export type ExternalPublicationPlatform = "csdn" | "juejin";

export class ArticleContentTooLargeError extends Error {
  constructor() {
    super("Article content exceeds the Worker delivery limit");
    this.name = "ArticleContentTooLargeError";
  }
}

export interface BlogSyncResult {
  platform: ExternalPublicationPlatform;
  externalId: string;
  url: string | null;
}

export interface OwnedBlogPost {
  id: number;
  userId: number;
  title: string;
  summary: string;
  contentInline: string | null;
  contentObjectKey: string | null;
  category: string;
  categoryName: string | null;
  coverImage: string | null;
  tags: string[];
}

export interface ContentPublicationRepository {
  findOwnedPost(userId: number, postId: number): Promise<OwnedBlogPost | null>;
  findSucceeded(userId: number, postId: number, platform: ExternalPublicationPlatform): Promise<BlogSyncResult | null>;
  countCsdnPublishedSince(userId: number, startOfDay: string, startOfNextDay: string): Promise<number>;
  claimCsdnCooldown(userId: number, now: string, leaseUntil: string): Promise<boolean>;
  claimAttempt(input: {
    userId: number;
    postId: number;
    platform: ExternalPublicationPlatform;
    attemptToken: string;
    now: string;
    leaseUntil: string;
  }): Promise<boolean>;
  markSucceeded(input: {
    userId: number;
    postId: number;
    platform: ExternalPublicationPlatform;
    attemptToken: string;
    result: BlogSyncResult;
    publishedAt: string;
  }): Promise<boolean>;
  markFailed(input: {
    userId: number;
    postId: number;
    platform: ExternalPublicationPlatform;
    attemptToken: string;
    errorCode: string;
    errorMessage: string;
    updatedAt: string;
  }): Promise<void>;
}

export interface ArticleContentReader {
  read(objectKey: string): Promise<string>;
}

export interface PlatformPublicationPort {
  publish(input: {
    userId: number;
    postId: number;
    platform: ExternalPublicationPlatform;
    juejinCategoryId?: string | null;
    juejinTagIds?: string[] | null;
    post: {
      id: number;
      title: string;
      summary: string;
      content: string;
      category: string;
      categoryName: string | null;
      coverImage: string | null;
      tags: string[];
    };
  }): Promise<BlogSyncResult>;
}
