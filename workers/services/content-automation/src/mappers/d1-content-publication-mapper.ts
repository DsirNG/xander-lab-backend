import type { D1Binding } from "@dinqor/worker-runtime";
import type {
  BlogSyncResult,
  ContentPublicationRepository,
  ExternalPublicationPlatform,
  OwnedBlogPost,
} from "../interfaces/external-publication.js";

interface OwnedPostRow {
  id: number;
  userId: number;
  title: string;
  summary: string;
  contentInline: string | null;
  contentObjectKey: string | null;
  category: string;
  categoryName: string | null;
  coverImage: string | null;
  tagsRaw: string | null;
}

interface SyncRow {
  platform: ExternalPublicationPlatform;
  externalId: string;
  url: string | null;
}

export class D1ContentPublicationMapper implements ContentPublicationRepository {
  constructor(private readonly db: D1Binding) {}

  async findOwnedPost(userId: number, postId: number): Promise<OwnedBlogPost | null> {
    const row = await this.db.prepare(`
      SELECT p.id, p.user_id AS userId, p.title, p.summary,
             p.content_inline AS contentInline,
             p.content_object_key AS contentObjectKey,
             p.category_id AS category, c.name AS categoryName,
             p.cover_image AS coverImage,
             COALESCE((
               SELECT group_concat(ordered_tags.name, ',')
               FROM (
                 SELECT t.name FROM blog_post_tag pt
                 INNER JOIN blog_tag t ON t.id = pt.tag_id
                 WHERE pt.post_id = p.id
                 ORDER BY t.name COLLATE NOCASE
               ) AS ordered_tags
             ), '') AS tagsRaw
        FROM blog_post p
        LEFT JOIN blog_category c ON c.id = p.category_id
       WHERE p.id = ? AND p.user_id = ?
    `).bind(postId, userId).first<OwnedPostRow>();
    if (!row) return null;
    return {
      id: row.id,
      userId: row.userId,
      title: row.title,
      summary: row.summary,
      contentInline: row.contentInline,
      contentObjectKey: row.contentObjectKey,
      category: row.category,
      categoryName: row.categoryName,
      coverImage: row.coverImage,
      tags: row.tagsRaw ? row.tagsRaw.split(",") : [],
    };
  }

  async findSucceeded(
    userId: number,
    postId: number,
    platform: ExternalPublicationPlatform,
  ): Promise<BlogSyncResult | null> {
    const row = await this.db.prepare(`
      SELECT platform, external_id AS externalId, url
        FROM blog_publish_record
       WHERE task_id = ? AND user_id = ? AND platform = ? AND status = 'SUCCEEDED'
         AND external_id IS NOT NULL AND external_id <> ''
       ORDER BY id DESC LIMIT 1
    `).bind(-postId, userId, platform).first<SyncRow>();
    return row ? { platform: row.platform, externalId: row.externalId, url: row.url } : null;
  }

  async countCsdnPublishedSince(userId: number, startOfDay: string, startOfNextDay: string): Promise<number> {
    const row = await this.db.prepare(`
      SELECT COUNT(*) AS count
        FROM blog_publish_record
       WHERE user_id = ? AND platform = 'csdn' AND status = 'SUCCEEDED'
         AND published_at >= ? AND published_at < ?
    `).bind(userId, startOfDay, startOfNextDay).first<{ count: number }>();
    return Number(row?.count ?? 0);
  }

  async claimCsdnCooldown(userId: number, now: string, leaseUntil: string): Promise<boolean> {
    const result = await this.db.prepare(`
      INSERT INTO csdn_publication_cooldown (user_id, expires_at)
      VALUES (?, ?)
      ON CONFLICT(user_id) DO UPDATE SET expires_at = excluded.expires_at
       WHERE csdn_publication_cooldown.expires_at <= ?
    `).bind(userId, leaseUntil, now).run();
    if (!result.success) throw new Error("Unable to claim CSDN publication cooldown");
    return result.meta.changes === 1;
  }

  async claimAttempt(input: {
    userId: number;
    postId: number;
    platform: ExternalPublicationPlatform;
    attemptToken: string;
    now: string;
    leaseUntil: string;
  }): Promise<boolean> {
    const result = await this.db.prepare(`
      INSERT INTO blog_publish_record
        (task_id, user_id, platform, status, attempts, attempt_token, lease_until,
         error_code, error_message, created_at, updated_at)
      VALUES (?, ?, ?, 'PENDING', 1, ?, ?, NULL, NULL, ?, ?)
      ON CONFLICT(task_id, platform) DO UPDATE SET
        status = 'PENDING',
        attempts = blog_publish_record.attempts + 1,
        attempt_token = excluded.attempt_token,
        lease_until = excluded.lease_until,
        error_code = NULL,
        error_message = NULL,
        updated_at = excluded.updated_at
      WHERE blog_publish_record.user_id = excluded.user_id
        AND blog_publish_record.status <> 'SUCCEEDED'
        AND (blog_publish_record.status <> 'PENDING'
          OR blog_publish_record.lease_until IS NULL
          OR blog_publish_record.lease_until <= ?)
    `).bind(
      -input.postId, input.userId, input.platform, input.attemptToken, input.leaseUntil,
      input.now, input.now, input.now,
    ).run();
    if (!result.success) throw new Error("Unable to claim publication attempt");
    return result.meta.changes === 1;
  }

  async markSucceeded(input: {
    userId: number;
    postId: number;
    platform: ExternalPublicationPlatform;
    attemptToken: string;
    result: BlogSyncResult;
    publishedAt: string;
  }): Promise<boolean> {
    const result = await this.db.prepare(`
      UPDATE blog_publish_record
         SET status = 'SUCCEEDED', external_id = ?, url = ?, error_code = NULL,
             error_message = NULL, attempt_token = NULL, lease_until = NULL,
             published_at = ?, updated_at = ?
       WHERE task_id = ? AND user_id = ? AND platform = ? AND status = 'PENDING'
         AND attempt_token = ?
    `).bind(
      input.result.externalId, input.result.url, input.publishedAt, input.publishedAt,
      -input.postId, input.userId, input.platform, input.attemptToken,
    ).run();
    if (!result.success) throw new Error("Unable to store publication result");
    return result.meta.changes === 1;
  }

  async markFailed(input: {
    userId: number;
    postId: number;
    platform: ExternalPublicationPlatform;
    attemptToken: string;
    errorCode: string;
    errorMessage: string;
    updatedAt: string;
  }): Promise<void> {
    const result = await this.db.prepare(`
      UPDATE blog_publish_record
         SET status = 'FAILED', error_code = ?, error_message = ?,
             attempt_token = NULL, lease_until = NULL, updated_at = ?
       WHERE task_id = ? AND user_id = ? AND platform = ? AND status = 'PENDING'
         AND attempt_token = ?
    `).bind(
      input.errorCode, input.errorMessage.slice(0, 1_000), input.updatedAt,
      -input.postId, input.userId, input.platform, input.attemptToken,
    ).run();
    if (!result.success) throw new Error("Unable to record publication failure");
  }
}
