import type { D1Binding } from "@dinqor/worker-runtime";
import type {
  AgentConversationRecord,
  AgentRunEvent,
  AgentRunMessage,
  AgentSpecialMessageKind,
  AgentTurnRepository,
} from "../interfaces/agent-run-repository.js";

const CONVERSATION_COLUMNS = `
  id, user_id AS userId, title, status, error_message AS errorMessage,
  run_version AS runVersion, cancel_requested AS cancelRequested,
  plan_json AS planJson, share_token AS shareToken, is_pinned AS isPinned,
  last_read_at AS lastReadAt, last_completed_at AS lastCompletedAt,
  created_at AS createdAt, updated_at AS updatedAt`;

export class D1AgentRunMapper implements AgentTurnRepository {
  constructor(private readonly db: D1Binding) {}

  findOwnedConversation(userId: number, conversationId: number): Promise<AgentConversationRecord | null> {
    return this.db.prepare(`
      SELECT ${CONVERSATION_COLUMNS} FROM agent_conversation WHERE id = ? AND user_id = ?
    `).bind(conversationId, userId).first<AgentConversationRecord>();
  }

  async claimTurn(
    userId: number,
    conversationId: number,
    content: string,
    attachments: unknown[],
  ): Promise<number | null> {
    const claimed = await this.db.prepare(`
      UPDATE agent_conversation
      SET status = 'running', error_message = NULL, cancel_requested = 0,
          run_version = run_version + 1,
          updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
      WHERE id = ? AND user_id = ? AND status <> 'running'
      RETURNING run_version AS runVersion
    `).bind(conversationId, userId).first<{ runVersion: number }>();
    if (!claimed) return null;
    await this.db.prepare(`
      INSERT INTO agent_message (conversation_id, run_version, role, kind, content, attachments_json)
      VALUES (?, ?, 'user', 'message', ?, ?)
    `).bind(conversationId, claimed.runVersion, content, JSON.stringify(attachments)).run();
    return claimed.runVersion;
  }

  async recentMessages(conversationId: number): Promise<AgentRunMessage[]> {
    const result = await this.db.prepare(`
      SELECT id, role, content, attachmentsJson FROM (
        SELECT id, role, content, attachments_json AS attachmentsJson FROM agent_message
        WHERE conversation_id = ? AND role IN ('user', 'assistant')
        ORDER BY id DESC LIMIT 40
      ) ORDER BY id ASC
    `).bind(conversationId).all<AgentRunMessage>();
    return result.results;
  }

  async appendEvent(
    conversationId: number,
    runVersion: number,
    eventType: string,
    data: unknown,
  ): Promise<number> {
    const row = await this.db.prepare(`
      INSERT INTO agent_event (conversation_id, run_version, event_type, data_content)
      VALUES (?, ?, ?, ?) RETURNING id
    `).bind(conversationId, runVersion, eventType, JSON.stringify(data)).first<{ id: number }>();
    if (!row) throw new Error("智能体事件保存失败");
    return row.id;
  }

  async saveSpecialMessage(
    userId: number,
    conversationId: number,
    runVersion: number,
    kind: AgentSpecialMessageKind,
    content: unknown,
  ): Promise<void> {
    const row = await this.db.prepare(`
      INSERT INTO agent_message (conversation_id, run_version, role, kind, content)
      SELECT ?, ?, 'assistant', ?, ?
      WHERE EXISTS (
        SELECT 1 FROM agent_conversation
        WHERE id = ? AND user_id = ? AND run_version = ? AND status = 'running'
      )
      RETURNING id
    `).bind(conversationId, runVersion, kind, JSON.stringify(content), conversationId, userId, runVersion)
      .first<{ id: number }>();
    if (!row) throw new Error("本轮对话已结束或被替代");
  }

  async finishTurn(userId: number, conversationId: number, runVersion: number, answer: string): Promise<{ answerEventId: number; completeEventId: number }> {
    const result = await this.db.prepare(`
      UPDATE agent_conversation
      SET status = 'ready', error_message = NULL, cancel_requested = 0,
          last_completed_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now'),
          updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
      WHERE id = ? AND user_id = ? AND run_version = ? AND status = 'running'
    `).bind(conversationId, userId, runVersion).run();
    if (result.meta.changes !== 1) throw new Error("本轮对话已结束或被替代");
    await this.db.prepare(`
      INSERT INTO agent_message (conversation_id, run_version, role, kind, content)
      VALUES (?, ?, 'assistant', 'answer', ?)
    `).bind(conversationId, runVersion, answer).run();
    const answerEventId = await this.appendEvent(conversationId, runVersion, "answer", answer);
    const completeEventId = await this.appendEvent(conversationId, runVersion, "complete", { status: "ready" });
    return { answerEventId, completeEventId };
  }

  async failTurn(userId: number, conversationId: number, runVersion: number, message: string): Promise<number> {
    await this.db.prepare(`
      UPDATE agent_conversation
      SET status = 'failed', error_message = ?, cancel_requested = 0,
          last_completed_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now'),
          updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
      WHERE id = ? AND user_id = ? AND run_version = ? AND status = 'running'
    `).bind(message, conversationId, userId, runVersion).run();
    return this.appendEvent(conversationId, runVersion, "error", message);
  }

  cancelTurn(userId: number, conversationId: number): Promise<AgentConversationRecord | null> {
    return this.db.prepare(`
      UPDATE agent_conversation SET cancel_requested = CASE WHEN status = 'running' THEN 1 ELSE 0 END
      WHERE id = ? AND user_id = ? RETURNING ${CONVERSATION_COLUMNS}
    `).bind(conversationId, userId).first<AgentConversationRecord>();
  }

  async isCancellationRequested(userId: number, conversationId: number, runVersion: number): Promise<boolean> {
    const row = await this.db.prepare(`
      SELECT cancel_requested AS cancelRequested FROM agent_conversation
      WHERE id = ? AND user_id = ? AND run_version = ? AND status = 'running'
    `).bind(conversationId, userId, runVersion).first<{ cancelRequested: number }>();
    return row?.cancelRequested === 1;
  }

  async listEventsAfter(conversationId: number, runVersion: number, afterEventId: number): Promise<AgentRunEvent[]> {
    const result = await this.db.prepare(`
      SELECT id, event_type AS eventType, data_content AS dataContent
      FROM agent_event WHERE conversation_id = ? AND run_version = ? AND id > ?
      ORDER BY id ASC LIMIT 100
    `).bind(conversationId, runVersion, afterEventId).all<AgentRunEvent>();
    return result.results;
  }
}
