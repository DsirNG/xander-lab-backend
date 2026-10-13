import type { D1Binding } from "@dinqor/worker-runtime";
import type {
  AgentConversationRepository,
  AgentMessageRecord,
  AgentWorkspaceResult,
} from "../interfaces/agent-conversation-repository.js";
import type { AgentConversationRecord } from "../interfaces/agent-run-repository.js";

const CONVERSATION_COLUMNS = `
  id, user_id AS userId, title, status, error_message AS errorMessage,
  run_version AS runVersion, cancel_requested AS cancelRequested,
  plan_json AS planJson, share_token AS shareToken, is_pinned AS isPinned,
  last_read_at AS lastReadAt, last_completed_at AS lastCompletedAt,
  created_at AS createdAt, updated_at AS updatedAt`;

export class D1AgentConversationMapper implements AgentConversationRepository {
  constructor(private readonly db: D1Binding) {}

  createConversation(userId: number, title: string): Promise<AgentConversationRecord | null> {
    return this.db.prepare(`
      INSERT INTO agent_conversation (user_id, title, status, run_version, cancel_requested, is_pinned)
      VALUES (?, ?, 'ready', 1, 0, 0)
      RETURNING ${CONVERSATION_COLUMNS}
    `).bind(userId, title).first<AgentConversationRecord>();
  }

  async listConversations(
    userId: number,
    limit: number,
    offset: number,
    search: string | null,
  ): Promise<AgentConversationRecord[]> {
    const escapedSearch = search?.replace(/[!%_]/g, (character) => `!${character}`);
    const statement = search
      ? this.db.prepare(`
          SELECT ${CONVERSATION_COLUMNS} FROM agent_conversation
          WHERE user_id = ? AND title LIKE ? ESCAPE '!'
          ORDER BY is_pinned DESC, updated_at DESC, id DESC
          LIMIT ? OFFSET ?
        `).bind(userId, `%${escapedSearch}%`, limit, offset)
      : this.db.prepare(`
          SELECT ${CONVERSATION_COLUMNS} FROM agent_conversation
          WHERE user_id = ?
          ORDER BY is_pinned DESC, updated_at DESC, id DESC
          LIMIT ? OFFSET ?
        `).bind(userId, limit, offset);
    const rows = await statement.all<AgentConversationRecord>();
    return rows.results;
  }

  findOwnedConversation(userId: number, conversationId: number): Promise<AgentConversationRecord | null> {
    return this.db.prepare(`
      SELECT ${CONVERSATION_COLUMNS} FROM agent_conversation WHERE id = ? AND user_id = ?
    `).bind(conversationId, userId).first<AgentConversationRecord>();
  }

  async listMessages(conversationId: number): Promise<AgentMessageRecord[]> {
    const rows = await this.db.prepare(`
      SELECT id, conversation_id AS conversationId, run_version AS runVersion, role, kind,
             tool_name AS toolName, invocation_id AS invocationId, content,
             attachments_json AS attachmentsJson, created_at AS createdAt
      FROM agent_message WHERE conversation_id = ? ORDER BY id ASC
    `).bind(conversationId).all<AgentMessageRecord>();
    return rows.results;
  }

  async listWorkspaceResults(conversationId: number): Promise<AgentWorkspaceResult[]> {
    // Tool results already live in the replay log. Project the latest bounded set
    // after ownership is checked by the service; do not duplicate persisted writes.
    const rows = await this.db.prepare(`
      SELECT id, run_version AS runVersion, data_content AS dataContent
      FROM agent_event
      WHERE conversation_id = ? AND event_type IN ('tool_result', 'tool_end')
        AND json_valid(data_content)
        AND json_extract(data_content, '$.tool') IN ('blog_generate', 'recall_memory', 'remember', 'publish_post')
      ORDER BY id DESC LIMIT 100
    `).bind(conversationId).all<AgentWorkspaceResult>();
    return rows.results.reverse();
  }

  findSharedConversation(shareToken: string): Promise<AgentConversationRecord | null> {
    return this.db.prepare(`
      SELECT ${CONVERSATION_COLUMNS} FROM agent_conversation WHERE share_token = ?
    `).bind(shareToken).first<AgentConversationRecord>();
  }

  updateReadTime(userId: number, conversationId: number, value: string): Promise<AgentConversationRecord | null> {
    return this.db.prepare(`
      UPDATE agent_conversation SET last_read_at = ?, updated_at = updated_at
      WHERE id = ? AND user_id = ? RETURNING ${CONVERSATION_COLUMNS}
    `).bind(value, conversationId, userId).first<AgentConversationRecord>();
  }

  updatePinned(userId: number, conversationId: number, pinned: boolean): Promise<AgentConversationRecord | null> {
    return this.db.prepare(`
      UPDATE agent_conversation SET is_pinned = ?, updated_at = updated_at
      WHERE id = ? AND user_id = ? RETURNING ${CONVERSATION_COLUMNS}
    `).bind(pinned ? 1 : 0, conversationId, userId).first<AgentConversationRecord>();
  }

  async enableShare(userId: number, conversationId: number, candidate: string): Promise<string | null | undefined> {
    const row = await this.db.prepare(`
      UPDATE agent_conversation SET share_token = COALESCE(share_token, ?)
      WHERE id = ? AND user_id = ? RETURNING share_token AS shareToken
    `).bind(candidate, conversationId, userId).first<{ shareToken: string | null }>();
    return row ? row.shareToken : undefined;
  }

  async disableShare(userId: number, conversationId: number): Promise<boolean> {
    const result = await this.db.prepare(`
      UPDATE agent_conversation SET share_token = NULL WHERE id = ? AND user_id = ?
    `).bind(conversationId, userId).run();
    return result.meta.changes > 0;
  }
}
