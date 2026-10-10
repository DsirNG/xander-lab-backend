import type { D1Binding } from "@dinqor/worker-runtime";
import type { AgentMemoryRepository, AgentMemoryRow, AgentMemoryWrite } from "../interfaces/agent-memory-repository.js";

const COLUMNS = `
  id, user_id AS userId, memory_key AS memoryKey, kind, content,
  source_conversation_id AS sourceConversationId, hit_count AS hitCount,
  last_used_at AS lastUsedAt, created_at AS createdAt, updated_at AS updatedAt
`;

export class D1AgentMemoryMapper implements AgentMemoryRepository {
  constructor(private readonly db: D1Binding) {}

  async upsert(input: AgentMemoryWrite): Promise<AgentMemoryRow | null> {
    await this.db.batch([
      this.db.prepare(`
        DELETE FROM agent_memory
        WHERE user_id = ? AND NOT EXISTS (
          SELECT 1 FROM agent_memory WHERE user_id = ? AND memory_key = ?
        ) AND id IN (
          SELECT id FROM agent_memory WHERE user_id = ?
          ORDER BY hit_count ASC, updated_at ASC, id ASC
          LIMIT max((SELECT COUNT(*) FROM agent_memory WHERE user_id = ?) - 199, 0)
        )
      `).bind(input.userId, input.userId, input.memoryKey, input.userId, input.userId),
      this.db.prepare(`
        INSERT INTO agent_memory (
          user_id, memory_key, kind, content, source_conversation_id
        ) VALUES (?, ?, ?, ?, ?)
        ON CONFLICT(user_id, memory_key) DO UPDATE SET
          kind = excluded.kind,
          content = excluded.content,
          updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
      `).bind(input.userId, input.memoryKey, input.kind, input.content, input.sourceConversationId),
    ]);
    return this.db.prepare(`
      SELECT ${COLUMNS} FROM agent_memory WHERE user_id = ? AND memory_key = ? LIMIT 1
    `).bind(input.userId, input.memoryKey).first<AgentMemoryRow>();
  }

  async delete(userId: number, memoryKey: string): Promise<boolean> {
    const result = await this.db.prepare(`
      DELETE FROM agent_memory WHERE user_id = ? AND memory_key = ?
    `).bind(userId, memoryKey).run();
    return result.meta.changes > 0;
  }

  async list(userId: number, limit: number): Promise<AgentMemoryRow[]> {
    const result = await this.db.prepare(`
      SELECT ${COLUMNS} FROM agent_memory
      WHERE user_id = ? ORDER BY updated_at DESC, id DESC LIMIT ?
    `).bind(userId, limit).all<AgentMemoryRow>();
    return result.results;
  }

  async markRecalled(userId: number, ids: number[], usedAt: string): Promise<void> {
    if (ids.length === 0) return;
    const placeholders = ids.map(() => "?").join(", ");
    await this.db.prepare(`
      UPDATE agent_memory SET hit_count = hit_count + 1, last_used_at = ?
      WHERE user_id = ? AND id IN (${placeholders})
    `).bind(usedAt, userId, ...ids).run();
  }
}
