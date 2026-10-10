import type { D1Binding } from "@dinqor/worker-runtime";
import type { AgentAttachmentRecord, AgentAttachmentRepository } from "../interfaces/agent-attachment.js";

const COLUMNS = `id, user_id AS userId, object_key AS objectKey, name, content_type AS contentType,
  size_bytes AS size, media_asset_id AS mediaAssetId, created_at AS createdAt`;

export class D1AgentAttachmentMapper implements AgentAttachmentRepository {
  constructor(private readonly db: D1Binding) {}

  create(input: Omit<AgentAttachmentRecord, "id" | "createdAt">): Promise<AgentAttachmentRecord | null> {
    return this.db.prepare(`
      INSERT INTO agent_attachment (user_id, object_key, name, content_type, size_bytes, media_asset_id)
      VALUES (?, ?, ?, ?, ?, ?)
      RETURNING ${COLUMNS}
    `).bind(input.userId, input.objectKey, input.name, input.contentType, input.size, input.mediaAssetId)
      .first<AgentAttachmentRecord>();
  }

  findOwned(userId: number, id: number): Promise<AgentAttachmentRecord | null> {
    return this.db.prepare(`SELECT ${COLUMNS} FROM agent_attachment WHERE id = ? AND user_id = ?`)
      .bind(id, userId).first<AgentAttachmentRecord>();
  }

  async findOwnedMany(userId: number, ids: number[]): Promise<AgentAttachmentRecord[]> {
    if (ids.length === 0) return [];
    const placeholders = ids.map(() => "?").join(", ");
    const result = await this.db.prepare(`
      SELECT ${COLUMNS} FROM agent_attachment WHERE user_id = ? AND id IN (${placeholders})
    `).bind(userId, ...ids).all<AgentAttachmentRecord>();
    return result.results;
  }
}
