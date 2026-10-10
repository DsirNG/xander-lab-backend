import type { D1Binding } from "@dinqor/worker-runtime";
import type { BlogMediaAsset, BlogMediaInsert, BlogMediaRepository } from "../interfaces/blog-media-repository.js";

const COLUMNS = `id, user_id AS userId, url, original_name AS originalName,
  stored_name AS storedName, size, content_type AS contentType, width, height,
  source_type AS sourceType, agent_task_id AS agentTaskId,
  generation_meta AS generationMeta, created_at AS createdAt`;

export class D1BlogMediaMapper implements BlogMediaRepository {
  constructor(private readonly db: D1Binding) {}

  async listImages(userId: number, contentType: string | null, keyword: string | null, limit: number): Promise<BlogMediaAsset[]> {
    const filters = ["user_id = ?"];
    const values: (number | string | null)[] = [userId];
    if (contentType) { filters.push("content_type = ?"); values.push(contentType); }
    if (keyword) { filters.push("original_name LIKE ?"); values.push(`%${keyword}%`); }
    values.push(limit);
    const result = await this.db.prepare(`SELECT ${COLUMNS} FROM blog_media_asset
      WHERE ${filters.join(" AND ")} ORDER BY created_at DESC, id DESC LIMIT ?`)
      .bind(...values).all<BlogMediaAsset>();
    return result.results;
  }

  async countAgentImages(userId: number): Promise<number> {
    const row = await this.db.prepare(`SELECT COUNT(*) AS count FROM blog_media_asset
      WHERE user_id = ? AND source_type = 'agent_generated'`).bind(userId).first<{ count: number }>();
    return row?.count ?? 0;
  }

  async listAgentImages(userId: number, limit: number, offset: number): Promise<BlogMediaAsset[]> {
    const result = await this.db.prepare(`SELECT ${COLUMNS} FROM blog_media_asset
      WHERE user_id = ? AND source_type = 'agent_generated'
      ORDER BY id DESC LIMIT ? OFFSET ?`).bind(userId, limit, offset).all<BlogMediaAsset>();
    return result.results;
  }

  findOwned(userId: number, id: number): Promise<BlogMediaAsset | null> {
    return this.db.prepare(`SELECT ${COLUMNS} FROM blog_media_asset WHERE id = ? AND user_id = ? LIMIT 1`)
      .bind(id, userId).first<BlogMediaAsset>();
  }

  async listByTask(userId: number, taskId: number): Promise<BlogMediaAsset[]> {
    const result = await this.db.prepare(`SELECT ${COLUMNS} FROM blog_media_asset
      WHERE user_id = ? AND agent_task_id = ? ORDER BY id ASC`).bind(userId, taskId).all<BlogMediaAsset>();
    return result.results;
  }

  async listRecentGenerated(userId: number, limit: number): Promise<BlogMediaAsset[]> {
    const result = await this.db.prepare(`SELECT ${COLUMNS} FROM blog_media_asset
      WHERE user_id = ? AND source_type = 'agent_generated'
      ORDER BY id DESC LIMIT ?`).bind(userId, limit).all<BlogMediaAsset>();
    return result.results;
  }

  async insert(input: BlogMediaInsert): Promise<BlogMediaAsset> {
    const row = await this.db.prepare(`INSERT INTO blog_media_asset
      (user_id, url, original_name, stored_name, size, content_type, width, height,
       source_type, agent_task_id, generation_meta)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      RETURNING ${COLUMNS}`)
      .bind(input.userId, input.url, input.originalName, input.storedName, input.size,
        input.contentType, input.width ?? null, input.height ?? null, input.sourceType,
        input.agentTaskId ?? null, input.generationMeta ?? null)
      .first<BlogMediaAsset>();
    if (!row || !Number.isSafeInteger(row.id) || row.id < 1) throw new Error("Blog media asset insert returned no row");
    return row;
  }

  async deleteOwned(userId: number, id: number): Promise<boolean> {
    const result = await this.db.prepare("DELETE FROM blog_media_asset WHERE id = ? AND user_id = ?")
      .bind(id, userId).run();
    return result.meta.changes === 1;
  }
}
