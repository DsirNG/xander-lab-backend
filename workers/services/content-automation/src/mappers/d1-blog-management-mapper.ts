import type { D1Binding } from "@dinqor/worker-runtime";
import type {
  BlogManagementPage, BlogManagementRepository, BlogManagementStats, BlogPublishRhythm, ManagedBlogPost,
} from "../interfaces/blog-management-repository.js";

type ManagedRow = Omit<ManagedBlogPost, "csdnSynced" | "juejinSynced"> & {
  tagsRaw: string | null;
  csdnSynced: number;
  juejinSynced: number;
};

const SELECT = `SELECT p.id, p.title, p.summary, p.content_inline AS contentInline,
  p.content_object_key AS contentObjectKey, p.category_id AS category, c.name AS categoryName,
  p.cover_image AS coverImage, p.user_id AS userId, p.author, p.published_at AS date,
  p.published_at AS publishedAt, p.read_time AS readTime, p.tips, p.views, p.status,
  EXISTS(SELECT 1 FROM blog_publish_record r WHERE r.task_id = -p.id
    AND r.platform = 'csdn' AND r.status = 'SUCCEEDED') AS csdnSynced,
  EXISTS(SELECT 1 FROM blog_publish_record r WHERE r.task_id = -p.id
    AND r.platform = 'juejin' AND r.status = 'SUCCEEDED') AS juejinSynced,
  (SELECT GROUP_CONCAT(t.name, ',') FROM blog_post_tag pt JOIN blog_tag t ON t.id = pt.tag_id
    WHERE pt.post_id = p.id) AS tagsRaw
  FROM blog_post p LEFT JOIN blog_category c ON c.id = p.category_id`;

function map(row: ManagedRow): ManagedBlogPost {
  const { tagsRaw, ...post } = row;
  return { ...post, tags: tagsRaw ? tagsRaw.split(",") : [],
    csdnSynced: row.csdnSynced !== 0, juejinSynced: row.juejinSynced !== 0 };
}

export class D1BlogManagementMapper implements BlogManagementRepository {
  constructor(private readonly db: D1Binding) {}

  async categoryExists(categoryId: string): Promise<boolean> {
    const row = await this.db.prepare("SELECT 1 AS found FROM blog_category WHERE id = ?")
      .bind(categoryId).first<{ found: number }>();
    return row?.found === 1;
  }

  async findById(postId: number): Promise<ManagedBlogPost | null> {
    const row = await this.db.prepare(`${SELECT} WHERE p.id = ?`).bind(postId).first<ManagedRow>();
    return row ? map(row) : null;
  }

  async findMine(userId: number, postId: number): Promise<ManagedBlogPost | null> {
    const row = await this.db.prepare(`${SELECT} WHERE p.id = ? AND p.user_id = ?`).bind(postId, userId).first<ManagedRow>();
    return row ? map(row) : null;
  }

  async listMine(userId: number, status: number | null, search: string, page: number, size: number): Promise<BlogManagementPage> {
    const predicates = ["p.user_id = ?"];
    const bindings: Array<string | number | null> = [userId];
    if (status === null) predicates.push("p.status IN (0, 1)");
    else { predicates.push("p.status = ?"); bindings.push(status); }
    if (search.trim()) {
      predicates.push("(p.title LIKE '%' || ? || '%' OR p.summary LIKE '%' || ? || '%')");
      bindings.push(search.trim(), search.trim());
    }
    const where = predicates.join(" AND ");
    const count = await this.db.prepare(`SELECT COUNT(*) AS count FROM blog_post p WHERE ${where}`)
      .bind(...bindings).first<{ count: number }>();
    const total = count?.count ?? 0;
    const rows = await this.db.prepare(`${SELECT} WHERE ${where}
      ORDER BY COALESCE(p.published_at, p.updated_at) DESC, p.id DESC LIMIT ? OFFSET ?`)
      .bind(...bindings, size, (page - 1) * size).all<ManagedRow>();
    const pages = total === 0 ? 0 : Math.ceil(total / size);
    return { records: rows.results.map(map), total, current: page, size, pages, hasMore: page < pages };
  }

  async update(userId: number, postId: number, patch: Record<string, string | number | null>, tags?: string[]): Promise<boolean> {
    const columns: Record<string, string> = {
      title: "title", summary: "summary", categoryId: "category_id", coverImage: "cover_image",
      contentInline: "content_inline", contentObjectKey: "content_object_key", readTime: "read_time",
    };
    const sets: string[] = [];
    const values: Array<string | number | null> = [];
    for (const [field, column] of Object.entries(columns)) {
      if (!(field in patch)) continue;
      sets.push(`${column} = ?`);
      values.push(patch[field]);
    }
    sets.push("updated_at = ?");
    values.push(new Date().toISOString(), postId, userId);
    const statements = [this.db.prepare(`UPDATE blog_post SET ${sets.join(", ")} WHERE id = ? AND user_id = ?`)
      .bind(...values)];
    if (tags !== undefined) {
      statements.push(this.db.prepare("DELETE FROM blog_post_tag WHERE post_id = ?").bind(postId));
      for (const name of tags) {
        statements.push(this.db.prepare("INSERT OR IGNORE INTO blog_tag(name) VALUES (?)").bind(name));
        statements.push(this.db.prepare(`INSERT OR IGNORE INTO blog_post_tag(post_id, tag_id)
          SELECT ?, id FROM blog_tag WHERE name = ?`).bind(postId, name));
      }
    }
    const results = await this.db.batch(statements);
    return results[0]?.meta.changes === 1;
  }

  async updateStatus(userId: number, postId: number, status: number, publishedAt: string | null): Promise<boolean> {
    const result = await this.db.prepare(`UPDATE blog_post SET status = ?,
      published_at = CASE WHEN ? IS NOT NULL THEN COALESCE(published_at, ?) ELSE published_at END,
      updated_at = ? WHERE id = ? AND user_id = ?`)
      .bind(status, publishedAt, publishedAt, new Date().toISOString(), postId, userId).run();
    return result.meta.changes === 1;
  }

  async moveToTrash(userId: number, postId: number): Promise<boolean> {
    const result = await this.db.prepare("UPDATE blog_post SET status = -1, updated_at = ? WHERE id = ? AND user_id = ?")
      .bind(new Date().toISOString(), postId, userId).run();
    return result.meta.changes === 1;
  }

  async permanentlyDelete(userId: number, postId: number): Promise<boolean> {
    const result = await this.db.batch([
      this.db.prepare("DELETE FROM blog_post_view WHERE post_id = ?").bind(postId),
      this.db.prepare("DELETE FROM blog_post_tag WHERE post_id = ?").bind(postId),
      this.db.prepare("DELETE FROM blog_publish_record WHERE task_id = ? AND user_id = ?").bind(-postId, userId),
      this.db.prepare("DELETE FROM blog_post WHERE id = ? AND user_id = ? AND status = -1").bind(postId, userId),
    ]);
    return result[3]?.meta.changes === 1;
  }

  async publishedPostId(userId: number, requestId: string): Promise<number | null> {
    const row = await this.db.prepare("SELECT id FROM blog_post WHERE user_id = ? AND idempotency_key = ?")
      .bind(userId, requestId).first<{ id: number }>();
    return row?.id ?? null;
  }

  async publishRhythm(userId: number, today: string): Promise<BlogPublishRhythm> {
    const start = new Date(`${today}T00:00:00.000Z`);
    start.setUTCDate(start.getUTCDate() - ((start.getUTCDay() + 6) % 7));
    const weekStart = start.toISOString().slice(0, 10);
    const weekEnd = new Date(start.getTime() + 6 * 86_400_000).toISOString().slice(0, 10);
    const result = await this.db.prepare(`SELECT published_at AS date, COUNT(*) AS count FROM blog_post
      WHERE user_id = ? AND status = 1 AND published_at BETWEEN ? AND ? GROUP BY published_at`)
      .bind(userId, weekStart, weekEnd).all<{ date: string; count: number }>();
    const counts = new Map(result.results.map((row) => [row.date, row.count]));
    const days = Array.from({ length: 7 }, (_, index) => {
      const date = new Date(start.getTime() + index * 86_400_000).toISOString().slice(0, 10);
      return { date, count: counts.get(date) ?? 0 };
    });
    const total = days.reduce((sum, item) => sum + item.count, 0);
    return { weekStart, dailyAverage: Math.round(total / 7 * 10) / 10, days };
  }

  async manageStats(userId: number, today: string): Promise<BlogManagementStats> {
    const start = new Date(`${today}T00:00:00.000Z`);
    start.setUTCDate(start.getUTCDate() - 6);
    const startDate = start.toISOString().slice(0, 10);
    const endExclusive = new Date(`${today}T00:00:00.000Z`);
    endExclusive.setUTCDate(endExclusive.getUTCDate() + 1);
    const counts = await this.db.prepare(`SELECT
      SUM(CASE WHEN status IN (0, 1) THEN 1 ELSE 0 END) AS totalArticles,
      SUM(CASE WHEN status = 0 THEN 1 ELSE 0 END) AS drafts,
      SUM(CASE WHEN status = 1 THEN 1 ELSE 0 END) AS published,
      SUM(CASE WHEN status = -1 THEN 1 ELSE 0 END) AS trashed
      FROM blog_post WHERE user_id = ?`).bind(userId).first<{
        totalArticles: number | null; drafts: number | null; published: number | null; trashed: number | null;
      }>();
    const views = await this.db.prepare(`SELECT substr(v.created_at, 1, 10) AS date, COUNT(*) AS views
      FROM blog_post_view v JOIN blog_post p ON p.id = v.post_id
      WHERE p.user_id = ? AND v.created_at >= ? AND v.created_at < ?
      GROUP BY substr(v.created_at, 1, 10) ORDER BY date`)
      .bind(userId, `${startDate}T00:00:00.000Z`, endExclusive.toISOString()).all<{ date: string; views: number }>();
    const byDate = new Map(views.results.map((row) => [row.date, row.views]));
    const dailyViews = Array.from({ length: 7 }, (_, index) => {
      const date = new Date(start.getTime() + index * 86_400_000).toISOString().slice(0, 10);
      return { date, views: byDate.get(date) ?? 0 };
    });
    const recentViews = dailyViews.reduce((sum, item) => sum + item.views, 0);
    const recentPublishedRow = await this.db.prepare(`SELECT COUNT(*) AS count FROM blog_post
      WHERE user_id = ? AND status = 1 AND published_at BETWEEN ? AND ?`)
      .bind(userId, startDate, today).first<{ count: number }>();
    const recentPublished = recentPublishedRow?.count ?? 0;
    return {
      totalArticles: counts?.totalArticles ?? 0, drafts: counts?.drafts ?? 0,
      published: counts?.published ?? 0, trashed: counts?.trashed ?? 0,
      recentViews, recentPublished,
      averageViews: recentPublished === 0 ? 0 : Math.round(recentViews / recentPublished * 10) / 10,
      dailyViews,
    };
  }
}
