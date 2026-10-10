import type { D1Binding } from "@dinqor/worker-runtime";
import type {
  BlogCategoryRecord,
  BlogPostPage,
  BlogPostRecord,
  BlogPublicRepository,
  BlogTagRecord,
  BlogViewInput,
} from "../interfaces/blog-public.js";

type SqlValue = string | number | null;

interface BlogPostRow extends Omit<BlogPostRecord, "tags"> {
  tagsRaw: string | null;
}

interface CountRow {
  total: number;
}

interface ManagedCountRow {
  count: number;
}

function postSelect(includeContent = false): string {
  const contentColumns = includeContent
    ? "p.content_inline AS contentInline, p.content_object_key AS contentObjectKey,"
    : "";
  return `
  SELECT p.id,
         p.title,
         p.summary,
         ${contentColumns}
         p.category_id AS category,
         c.name AS categoryName,
         p.cover_image AS coverImage,
         p.user_id AS userId,
         p.author,
         p.published_at AS date,
         p.read_time AS readTime,
         p.tips,
         p.views,
         p.status,
         EXISTS (
           SELECT 1 FROM blog_publish_record r
           WHERE r.task_id = -p.id AND r.platform = 'csdn' AND r.status = 'SUCCEEDED'
         ) AS csdnSynced,
         EXISTS (
           SELECT 1 FROM blog_publish_record r
           WHERE r.task_id = -p.id AND r.platform = 'juejin' AND r.status = 'SUCCEEDED'
         ) AS juejinSynced,
         COALESCE((
           SELECT group_concat(ordered_tags.name, ',')
           FROM (
             SELECT t.name
             FROM blog_post_tag pt
             INNER JOIN blog_tag t ON t.id = pt.tag_id
             WHERE pt.post_id = p.id
             ORDER BY t.name COLLATE NOCASE
           ) AS ordered_tags
         ), '') AS tagsRaw
  FROM blog_post p
  LEFT JOIN blog_category c ON c.id = p.category_id
`;
}

function mapPost(row: BlogPostRow): BlogPostRecord {
  const { tagsRaw, ...post } = row;
  return { ...post, tags: tagsRaw ? tagsRaw.split(",") : [] };
}

export class D1BlogPublicMapper implements BlogPublicRepository {
  constructor(private readonly db: D1Binding) {}

  async listPublished(input: {
    page: number;
    size: number;
    category: string;
    tag: string;
    search: string;
  }): Promise<BlogPostPage> {
    const filters = ["p.status = 1"];
    const values: SqlValue[] = [];
    if (input.category.trim()) {
      filters.push("p.category_id = ?");
      values.push(input.category);
    }
    if (input.tag.trim()) {
      filters.push(`EXISTS (
        SELECT 1 FROM blog_post_tag pt
        INNER JOIN blog_tag t ON t.id = pt.tag_id
        WHERE pt.post_id = p.id AND lower(t.name) = lower(?)
      )`);
      values.push(input.tag);
    }
    if (input.search.trim()) {
      filters.push(`(
        p.title LIKE '%' || ? || '%'
        OR p.summary LIKE '%' || ? || '%'
        OR EXISTS (
          SELECT 1 FROM blog_post_tag pt
          INNER JOIN blog_tag t ON t.id = pt.tag_id
          WHERE pt.post_id = p.id AND lower(t.name) LIKE '%' || lower(?) || '%'
        )
      )`);
      values.push(input.search, input.search, input.search);
    }

    const where = filters.join(" AND ");
    const countStatement = this.db.prepare(`SELECT COUNT(*) AS total FROM blog_post p WHERE ${where}`);
    const countQuery = values.length ? countStatement.bind(...values) : countStatement;
    const count = await countQuery.first<CountRow>();
    const total = count?.total ?? 0;
    const rows = await this.db.prepare(
      `${postSelect()}
       WHERE ${where}
       ORDER BY p.published_at DESC, p.id DESC
       LIMIT ? OFFSET ?`,
    ).bind(...values, input.size, (input.page - 1) * input.size).all<BlogPostRow>();
    return {
      records: rows.results.map(mapPost),
      total,
      current: input.page,
      pages: total === 0 ? 0 : Math.ceil(total / input.size),
      size: input.size,
      hasMore: input.page * input.size < total,
    };
  }

  async recentPublished(limit: number): Promise<BlogPostRecord[]> {
    const rows = await this.db.prepare(
      `${postSelect()} WHERE p.status = 1
       ORDER BY p.published_at DESC, p.id DESC LIMIT ?`,
    ).bind(limit).all<BlogPostRow>();
    return rows.results.map(mapPost);
  }

  async findPublishedPost(postId: number): Promise<BlogPostRecord | null> {
    const row = await this.db.prepare(
      `${postSelect(true)} WHERE p.id = ? AND p.status = 1`,
    ).bind(postId).first<BlogPostRow>();
    return row ? mapPost(row) : null;
  }

  async recordPublicView(input: BlogViewInput): Promise<boolean> {
    const results = await this.db.batch([
      this.db.prepare(
        "DELETE FROM blog_view_dedup WHERE dedupe_key = ? AND expires_at <= ?",
      ).bind(input.dedupeKey, input.now),
      this.db.prepare(
        `INSERT INTO blog_view_dedup (dedupe_key, event_id, expires_at, created_at)
         SELECT ?, ?, ?, ?
         WHERE EXISTS (SELECT 1 FROM blog_post WHERE id = ? AND status = 1)
         ON CONFLICT(dedupe_key) DO NOTHING`,
      ).bind(input.dedupeKey, input.eventId, input.expiresAt, input.now, input.postId),
      this.db.prepare(
        `INSERT INTO blog_post_view (event_id, post_id, user_id, ip_address, user_agent, created_at)
         SELECT ?, ?, NULL, ?, ?, ?
         WHERE EXISTS (
           SELECT 1 FROM blog_view_dedup WHERE dedupe_key = ? AND event_id = ?
         )
         ON CONFLICT(event_id) DO NOTHING`,
      ).bind(input.eventId, input.postId, input.ipAddress, input.userAgent,
        input.now, input.dedupeKey, input.eventId),
      this.db.prepare(
        `UPDATE blog_post SET views = views + 1, updated_at = ?
         WHERE id = ? AND status = 1
           AND EXISTS (SELECT 1 FROM blog_post_view WHERE event_id = ? AND post_id = ?)`,
      ).bind(input.now, input.postId, input.eventId, input.postId),
    ]);
    return results[3]?.meta.changes === 1;
  }

  async countManagedPosts(userId: number): Promise<number> {
    const result = await this.db.prepare(
      "SELECT COUNT(*) AS count FROM blog_post WHERE user_id = ? AND status IN (0, 1)",
    ).bind(userId).first<ManagedCountRow>();
    return result?.count ?? 0;
  }

  async listCategories(): Promise<BlogCategoryRecord[]> {
    const rows = await this.db.prepare(
      `SELECT c.id, c.name, COUNT(p.id) AS count
       FROM blog_category c
       LEFT JOIN blog_post p ON p.category_id = c.id AND p.status = 1
       GROUP BY c.id, c.name, c.sort ORDER BY c.sort ASC, c.id ASC`,
    ).all<BlogCategoryRecord>();
    return rows.results;
  }

  async listTags(limit?: number): Promise<BlogTagRecord[]> {
    const sql = `SELECT t.name, COUNT(p.id) AS count
                 FROM blog_tag t
                 LEFT JOIN blog_post_tag pt ON pt.tag_id = t.id
                 LEFT JOIN blog_post p ON p.id = pt.post_id AND p.status = 1
                 GROUP BY t.id, t.name
                 ORDER BY count DESC, t.name COLLATE NOCASE ASC`;
    const rows = limit === undefined
      ? await this.db.prepare(sql).all<BlogTagRecord>()
      : await this.db.prepare(`${sql} LIMIT ?`).bind(limit).all<BlogTagRecord>();
    return rows.results;
  }

  async cleanupExpiredViewDedup(now: string): Promise<void> {
    await this.db.prepare("DELETE FROM blog_view_dedup WHERE expires_at <= ?").bind(now).run();
  }
}
