import type { D1Binding } from "@dinqor/worker-runtime";
import { recoverStaleImageRun } from "./recover-stale-image-run.js";
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
  run_started_at AS runStartedAt,
  created_at AS createdAt, updated_at AS updatedAt`;

export class D1AgentConversationMapper implements AgentConversationRepository {
  constructor(private readonly db: D1Binding) {}

  rename(userId: number, conversationId: number, title: string): Promise<AgentConversationRecord | null> {
    return this.db.prepare(`UPDATE agent_conversation SET title = ?
      WHERE id = ? AND user_id = ? RETURNING ${CONVERSATION_COLUMNS}`)
      .bind(title, conversationId, userId).first<AgentConversationRecord>();
  }

  async fork(userId: number, conversationId: number, messageId: number, key: string, title: string): Promise<AgentConversationRecord | null> {
    // D1 batch is atomic. The per-user key makes a lost HTTP response safe to retry.
    await this.db.batch([
      this.db.prepare(`INSERT INTO agent_conversation
        (user_id, title, status, run_version, fork_key, fork_source_id, fork_message_id)
        SELECT c.user_id, ?, 'ready', c.run_version, ?, c.id, m.id
        FROM agent_conversation c JOIN agent_message m ON m.conversation_id = c.id
        WHERE c.user_id = ? AND c.id = ? AND m.id = ? AND m.role = 'user' AND m.kind = 'message'
        ON CONFLICT(user_id, fork_key) DO NOTHING`).bind(title, key, userId, conversationId, messageId),
      this.db.prepare(`INSERT INTO agent_message
        (conversation_id, run_version, role, kind, tool_name, invocation_id, content, attachments_json, created_at)
        SELECT target.id, m.run_version, m.role, m.kind, m.tool_name, m.invocation_id,
          m.content, m.attachments_json, m.created_at
        FROM agent_conversation target JOIN agent_message m ON m.conversation_id = target.fork_source_id
        WHERE target.user_id = ? AND target.fork_key = ?
          AND target.fork_source_id = ? AND target.fork_message_id = ? AND m.id < target.fork_message_id
          AND NOT EXISTS (SELECT 1 FROM agent_message existing WHERE existing.conversation_id = target.id)
        ORDER BY m.id`).bind(userId, key, conversationId, messageId),
    ]);
    return this.db.prepare(`SELECT ${CONVERSATION_COLUMNS} FROM agent_conversation
      WHERE user_id = ? AND fork_key = ? AND fork_source_id = ? AND fork_message_id = ?`)
      .bind(userId, key, conversationId, messageId).first<AgentConversationRecord>();
  }

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

  async findOwnedConversation(userId: number, conversationId: number): Promise<AgentConversationRecord | null> {
    const read = () => this.db.prepare(`
      SELECT ${CONVERSATION_COLUMNS} FROM agent_conversation WHERE id = ? AND user_id = ?
    `).bind(conversationId, userId).first<AgentConversationRecord>();
    const row = await read();
    return await recoverStaleImageRun(this.db, row) ? read() : row;
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
      SELECT id, run_version AS runVersion, event_type AS eventType,
        created_at AS createdAt, data_content AS dataContent
      FROM agent_event
      WHERE conversation_id = ? AND event_type IN ('tool_result', 'tool_end', 'tool_error')
        AND json_valid(data_content)
      ORDER BY id DESC LIMIT 500
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
