import type { D1Binding } from "@dinqor/worker-runtime";
import { recoverStaleImageRun } from "./recover-stale-image-run.js";
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
  run_started_at AS runStartedAt,
  created_at AS createdAt, updated_at AS updatedAt`;

export class D1AgentRunMapper implements AgentTurnRepository {
  constructor(private readonly db: D1Binding) {}

  async findOwnedConversation(userId: number, conversationId: number): Promise<AgentConversationRecord | null> {
    const read = () => this.db.prepare(`
      SELECT ${CONVERSATION_COLUMNS} FROM agent_conversation WHERE id = ? AND user_id = ?
    `).bind(conversationId, userId).first<AgentConversationRecord>();
    const row = await read();
    return await recoverStaleImageRun(this.db, row) ? read() : row;
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
          run_started_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now'),
          updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
      WHERE id = ? AND user_id = ? AND status NOT IN ('running', 'awaiting_approval')
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

  async recentToolResults(conversationId: number): Promise<unknown[]> {
    const rows = await this.db.prepare(`SELECT data_content AS content FROM agent_event WHERE conversation_id = ? AND event_type = 'tool_result' ORDER BY id DESC LIMIT 40`).bind(conversationId).all<{ content: string }>();
    return rows.results.reverse().flatMap(row => {
      try { return [JSON.parse(row.content) as unknown]; } catch { return []; }
    });
  }

  async appendEvent(
    conversationId: number,
    runVersion: number,
    eventType: string,
    data: unknown,
  ): Promise<number> {
    const row = await this.db.prepare(`
      INSERT INTO agent_event (conversation_id, run_version, event_type, data_content)
      SELECT ?, ?, ?, ? WHERE EXISTS (
        SELECT 1 FROM agent_conversation WHERE id = ? AND run_version = ?
          AND status = 'running' AND cancel_requested = 0
      ) RETURNING id
    `).bind(conversationId, runVersion, eventType, JSON.stringify(data), conversationId, runVersion).first<{ id: number }>();
    if (!row) throw new DOMException("The operation was aborted", "AbortError");
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
        WHERE id = ? AND user_id = ? AND run_version = ? AND status = 'running' AND cancel_requested = 0
      )
      RETURNING id
    `).bind(conversationId, runVersion, kind, JSON.stringify(content), conversationId, userId, runVersion)
      .first<{ id: number }>();
    if (!row) throw new Error("本轮对话已结束或被替代");
  }

  async finishTurn(userId: number, conversationId: number, runVersion: number, answer: string): Promise<{ completeEventId: number }> {
    // The answer, terminal event and ready state become visible atomically, so a
    // reconnect never observes a finished conversation with an incomplete snapshot.
    const results = await this.db.batch([
      this.db.prepare(`
        INSERT INTO agent_message (conversation_id, run_version, role, kind, content)
        SELECT ?, ?, 'assistant', 'answer', ?
        WHERE EXISTS (SELECT 1 FROM agent_conversation
          WHERE id = ? AND user_id = ? AND run_version = ? AND status = 'running' AND cancel_requested = 0)
      `).bind(conversationId, runVersion, answer, conversationId, userId, runVersion),
      this.db.prepare(`
        INSERT INTO agent_event (conversation_id, run_version, event_type, data_content)
        SELECT ?, ?, 'complete', ?
        WHERE EXISTS (SELECT 1 FROM agent_conversation
          WHERE id = ? AND user_id = ? AND run_version = ? AND status = 'running' AND cancel_requested = 0)
        RETURNING id
      `).bind(conversationId, runVersion, JSON.stringify({ status: "ready" }), conversationId, userId, runVersion),
      this.db.prepare(`
      UPDATE agent_conversation
      SET status = 'ready', error_message = NULL, cancel_requested = 0,
          last_completed_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now'),
          updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
      WHERE id = ? AND user_id = ? AND run_version = ? AND status = 'running' AND cancel_requested = 0
    `).bind(conversationId, userId, runVersion),
    ]);
    if (results[2].meta.changes !== 1) throw new Error("本轮对话已结束或被替代");
    const completeEventId = results[1].results?.[0]?.id;
    if (typeof completeEventId !== "number") throw new Error("智能体事件保存失败");
    return { completeEventId };
  }

  async failTurn(userId: number, conversationId: number, runVersion: number, message: string): Promise<number> {
    const results = await this.db.batch([
      this.db.prepare(`
        INSERT INTO agent_event (conversation_id, run_version, event_type, data_content)
        SELECT id, run_version, 'error', ? FROM agent_conversation
        WHERE id = ? AND user_id = ? AND run_version = ? AND status = 'running' AND cancel_requested = 0
        RETURNING id
      `).bind(JSON.stringify(message), conversationId, userId, runVersion),
      this.db.prepare(`
      UPDATE agent_conversation
      SET status = 'failed', error_message = ?, cancel_requested = 0,
          last_completed_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now'),
          updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
      WHERE id = ? AND user_id = ? AND run_version = ? AND status = 'running' AND cancel_requested = 0
    `).bind(message, conversationId, userId, runVersion),
    ]);
    const id = results[0].results?.[0]?.id;
    // A late worker must not report an error for an already completed or superseded run.
    return typeof id === "number" ? id : 0;
  }

  async requestCancellation(userId: number, conversationId: number, runVersion: number): Promise<boolean> {
    const result = await this.db.prepare(`UPDATE agent_conversation SET cancel_requested = 1
      WHERE id = ? AND user_id = ? AND run_version = ? AND status = 'running'`)
      .bind(conversationId, userId, runVersion).run();
    return result.meta.changes === 1;
  }

  async cancelTurn(userId: number, conversationId: number, expectedRunVersion?: number): Promise<AgentConversationRecord | null> {
    const current = await this.db.prepare(`SELECT ${CONVERSATION_COLUMNS} FROM agent_conversation
      WHERE id = ? AND user_id = ?`).bind(conversationId, userId).first<AgentConversationRecord>();
    if (!current || current.status !== "running") return current;
    if (expectedRunVersion !== undefined && current.runVersion !== expectedRunVersion) return current;
    // Revoke this version atomically with its terminal event. A delayed stop must
    // never cancel a newly claimed turn, and a disconnected executor is not needed.
    await this.db.batch([
      this.db.prepare(`INSERT INTO agent_event (conversation_id, run_version, event_type, data_content)
        SELECT id, run_version, 'complete', ? FROM agent_conversation
        WHERE id = ? AND user_id = ? AND run_version = ? AND status = 'running'`)
        .bind(JSON.stringify({ status: "ready", cancelled: true }), conversationId, userId, current.runVersion),
      this.db.prepare(`UPDATE agent_conversation SET status = 'ready', cancel_requested = 1,
        error_message = NULL, last_completed_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now'),
        updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
        WHERE id = ? AND user_id = ? AND run_version = ? AND status = 'running'`)
        .bind(conversationId, userId, current.runVersion),
    ]);
    return this.findOwnedConversation(userId, conversationId);
  }

  async isCancellationRequested(userId: number, conversationId: number, runVersion: number): Promise<boolean> {
    const row = await this.db.prepare(`
      SELECT cancel_requested AS cancelRequested FROM agent_conversation
      WHERE id = ? AND user_id = ? AND run_version = ? AND status = 'running'
    `).bind(conversationId, userId, runVersion).first<{ cancelRequested: number }>();
    // Completed, deleted and superseded versions have no execution authority.
    return !row || row.cancelRequested === 1;
  }

  async listEventsAfter(conversationId: number, runVersion: number, afterEventId: number): Promise<AgentRunEvent[]> {
    const result = await this.db.prepare(`
      SELECT id, event_type AS eventType, data_content AS dataContent,
             created_at AS createdAt
      FROM agent_event WHERE conversation_id = ? AND run_version = ? AND id > ?
      ORDER BY id ASC LIMIT 100
    `).bind(conversationId, runVersion, afterEventId).all<AgentRunEvent>();
    return result.results;
  }
}
