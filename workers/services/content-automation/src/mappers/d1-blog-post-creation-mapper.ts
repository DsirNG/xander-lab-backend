import type { D1Binding } from "@dinqor/worker-runtime";
import type {
  BlogPostCreationInput,
  BlogPostCreationRepository,
  CreatedBlogPost,
} from "../interfaces/blog-post-creation.js";

interface CreatedPostRow extends Omit<CreatedBlogPost, "tags"> {
  tagsRaw: string | null;
}

function selectCreatedPost(): string {
  return `
    SELECT p.id, p.title, p.summary, p.category_id AS category, c.name AS categoryName,
           p.cover_image AS coverImage, p.user_id AS userId, p.author,
           p.published_at AS date, p.read_time AS readTime, p.tips, p.views, p.status,
           p.content_inline AS contentInline, p.content_object_key AS contentObjectKey,
           p.request_hash AS requestHash,
           EXISTS (SELECT 1 FROM blog_publish_record r
             WHERE r.task_id = -p.id AND r.platform = 'csdn' AND r.status = 'SUCCEEDED') AS csdnSynced,
           EXISTS (SELECT 1 FROM blog_publish_record r
             WHERE r.task_id = -p.id AND r.platform = 'juejin' AND r.status = 'SUCCEEDED') AS juejinSynced,
           COALESCE((SELECT group_concat(ordered_tags.name, ',') FROM (
             SELECT t.name FROM blog_post_tag pt
             INNER JOIN blog_tag t ON t.id = pt.tag_id
             WHERE pt.post_id = p.id ORDER BY t.name COLLATE NOCASE
           ) AS ordered_tags), '') AS tagsRaw
      FROM blog_post p
      LEFT JOIN blog_category c ON c.id = p.category_id
  `;
}

function mapCreatedPost(row: CreatedPostRow): CreatedBlogPost {
  const { tagsRaw, ...post } = row;
  return { ...post, tags: tagsRaw ? tagsRaw.split(",") : [] };
}

export class D1BlogPostCreationMapper implements BlogPostCreationRepository {
  constructor(private readonly db: D1Binding) {}

  async categoryExists(categoryId: string): Promise<boolean> {
    const row = await this.db.prepare("SELECT 1 AS found FROM blog_category WHERE id = ?")
      .bind(categoryId).first<{ found: number }>();
    return row?.found === 1;
  }

  async findCreateReceipt(userId: number, idempotencyKey: string): Promise<CreatedBlogPost | null> {
    const row = await this.db.prepare(`${selectCreatedPost()}
      WHERE p.user_id = ? AND p.idempotency_key = ?
    `).bind(userId, idempotencyKey).first<CreatedPostRow>();
    return row ? mapCreatedPost(row) : null;
  }

  async createPost(input: BlogPostCreationInput): Promise<CreatedBlogPost> {
    const statements = [this.db.prepare(`
      INSERT INTO blog_post (
        title, summary, content_inline, content_object_key, category_id, cover_image,
        user_id, author, read_time, status, published_at, idempotency_key, request_hash, created_at, updated_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, 'DinQorAI', ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(user_id, idempotency_key) DO NOTHING
    `).bind(
      input.title, input.summary, input.contentInline, input.contentObjectKey, input.categoryId,
      input.coverImage, input.userId, input.readTime, input.publish ? 1 : 0, input.publishedAt,
      input.idempotencyKey, input.requestHash, input.now, input.now,
    )];

    for (const tag of input.tags) {
      statements.push(this.db.prepare("INSERT OR IGNORE INTO blog_tag (name) VALUES (?)").bind(tag));
    }
    for (const tag of input.tags) {
      statements.push(this.db.prepare(`
        INSERT OR IGNORE INTO blog_post_tag (post_id, tag_id)
        SELECT p.id, t.id
          FROM blog_post p INNER JOIN blog_tag t ON t.name = ?
         WHERE p.user_id = ? AND p.idempotency_key = ? AND p.request_hash = ?
      `).bind(tag, input.userId, input.idempotencyKey, input.requestHash));
    }
    const results = await this.db.batch(statements);
    if (results.some((result) => !result.success)) throw new Error("Unable to create blog post");

    const created = await this.findCreateReceipt(input.userId, input.idempotencyKey);
    if (!created) throw new Error("Blog post create receipt is missing");
    return created;
  }
}
