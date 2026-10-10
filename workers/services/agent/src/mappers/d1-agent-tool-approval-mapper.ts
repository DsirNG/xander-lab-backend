import type { D1Binding } from "@dinqor/worker-runtime";
import type {
  AgentApprovalDecision,
  AgentApprovalRecord,
  AgentToolApprovalRepository,
  PendingAgentApprovalInput,
} from "../interfaces/agent-tool-approval-repository.js";

const APPROVAL_COLUMNS = `
  id, user_id AS userId, conversation_id AS conversationId, run_version AS runVersion,
  invocation_id AS invocationId, tool_name AS toolName,
  request_fingerprint AS requestFingerprint, summary_json AS summaryJson,
  status, decision_reason AS decisionReason, decided_at AS decidedAt,
  created_at AS createdAt, updated_at AS updatedAt`;
const APPROVAL_LIST_COLUMNS = `
  approval.id AS id, approval.user_id AS userId,
  approval.conversation_id AS conversationId, approval.run_version AS runVersion,
  approval.invocation_id AS invocationId, approval.tool_name AS toolName,
  approval.request_fingerprint AS requestFingerprint, approval.summary_json AS summaryJson,
  approval.status AS status, approval.decision_reason AS decisionReason,
  approval.decided_at AS decidedAt, approval.created_at AS createdAt,
  approval.updated_at AS updatedAt`;

/** Persists approval snapshots and their corresponding invocation/event transitions in Agent D1. */
export class D1AgentToolApprovalMapper implements AgentToolApprovalRepository {
  constructor(private readonly db: D1Binding) {}

  async createPendingApproval(
    input: PendingAgentApprovalInput,
  ): Promise<{ approval: AgentApprovalRecord; eventId: number } | null> {
    const results = await this.db.batch([
      this.db.prepare(`
        UPDATE agent_conversation
        SET status = 'awaiting_approval', error_message = NULL,
            updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
        WHERE id = ? AND user_id = ? AND run_version = ? AND status = 'running'
        RETURNING id
      `).bind(input.conversationId, input.userId, input.runVersion),
      this.db.prepare(`
        INSERT INTO agent_tool_invocation (
          conversation_id, run_version, invocation_id, tool_name, arguments_json, status
        )
        SELECT ?, ?, ?, ?, ?, 'WAITING_APPROVAL'
        WHERE EXISTS (
          SELECT 1 FROM agent_conversation
          WHERE id = ? AND user_id = ? AND run_version = ? AND status = 'awaiting_approval'
        )
        RETURNING id
      `).bind(
        input.conversationId, input.runVersion, input.invocationId, input.toolName, input.argumentsJson,
        input.conversationId, input.userId, input.runVersion,
      ),
      this.db.prepare(`
        INSERT INTO agent_approval (
          user_id, conversation_id, run_version, invocation_id, tool_name,
          request_fingerprint, summary_json, status
        )
        SELECT ?, ?, ?, ?, ?, ?, ?, 'PENDING'
        WHERE EXISTS (SELECT 1 FROM agent_tool_invocation WHERE invocation_id = ?)
        RETURNING ${APPROVAL_COLUMNS}
      `).bind(
        input.userId, input.conversationId, input.runVersion, input.invocationId, input.toolName,
        input.requestFingerprint, input.summaryJson, input.invocationId,
      ),
      this.db.prepare(`
        INSERT INTO agent_message (
          conversation_id, run_version, role, kind, tool_name, invocation_id, content
        )
        SELECT ?, ?, 'assistant', 'tool_call', ?, ?, ?
        WHERE EXISTS (SELECT 1 FROM agent_approval WHERE invocation_id = ? AND status = 'PENDING')
      `).bind(
        input.conversationId, input.runVersion, input.toolName, input.invocationId,
        JSON.stringify({ tool: input.toolName, args: JSON.parse(input.argumentsJson) }), input.invocationId,
      ),
      this.db.prepare(`
        INSERT INTO agent_event (conversation_id, run_version, event_type, data_content)
        SELECT ?, ?, 'approval_required', json_object(
          'invocationId', ?, 'tool', ?,
          'approvalId', (SELECT id FROM agent_approval WHERE invocation_id = ?),
          'summary', json(?)
        )
        WHERE EXISTS (SELECT 1 FROM agent_approval WHERE invocation_id = ? AND status = 'PENDING')
        RETURNING id
      `).bind(
        input.conversationId,
        input.runVersion,
        input.invocationId,
        input.toolName,
        input.invocationId,
        input.summaryJson,
        input.invocationId,
      ),
    ]);

    if ((results[0]?.meta?.changes ?? 0) !== 1) return null;
    if ((results[1]?.meta?.changes ?? 0) !== 1 || (results[2]?.meta?.changes ?? 0) !== 1
      || (results[3]?.meta?.changes ?? 0) !== 1 || (results[4]?.meta?.changes ?? 0) !== 1) {
      throw new Error("Agent approval could not be persisted");
    }
    const approval = await this.db.prepare(`
      SELECT ${APPROVAL_COLUMNS} FROM agent_approval WHERE invocation_id = ?
    `).bind(input.invocationId).first<AgentApprovalRecord>();
    const event = await this.db.prepare(`
      SELECT id FROM agent_event
      WHERE conversation_id = ? AND run_version = ? AND event_type = 'approval_required'
        AND json_extract(data_content, '$.invocationId') = ?
    `).bind(input.conversationId, input.runVersion, input.invocationId).first<{ id: number }>();
    if (!approval || !event) throw new Error("Agent approval could not be persisted");

    return { approval, eventId: event.id };
  }

  async listApprovals(userId: number, conversationId: number): Promise<AgentApprovalRecord[] | null> {
    const owner = await this.db.prepare(`
      SELECT id FROM agent_conversation WHERE id = ? AND user_id = ?
    `).bind(conversationId, userId).first<{ id: number }>();
    if (!owner) return null;
    const rows = await this.db.prepare(`
      SELECT ${APPROVAL_LIST_COLUMNS}, invocation.status AS invocationStatus
      FROM agent_approval approval
      JOIN agent_tool_invocation invocation ON invocation.invocation_id = approval.invocation_id
      WHERE approval.conversation_id = ? AND approval.user_id = ?
      ORDER BY approval.id DESC
    `).bind(conversationId, userId).all<AgentApprovalRecord>();
    return rows.results;
  }

  async decideApproval(
    userId: number,
    conversationId: number,
    approvalId: number,
    approved: boolean,
    reason: string | null,
  ): Promise<AgentApprovalDecision> {
    const targetStatus = approved ? "APPROVED" : "REJECTED";
    const invocationStatus = "RUNNING";
    const decisionToken = crypto.randomUUID();
    const eventType = approved ? "approval_approved" : "approval_rejected";
    const results = await this.db.batch([
      this.db.prepare(`
        UPDATE agent_approval
        SET status = ?, decision_reason = ?, decision_token = ?,
            decided_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now'),
            updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
        WHERE id = ? AND user_id = ? AND conversation_id = ? AND status = 'PENDING'
          AND EXISTS (
            SELECT 1 FROM agent_conversation
            WHERE id = ? AND user_id = ? AND run_version = agent_approval.run_version
              AND status = 'awaiting_approval'
          )
        RETURNING ${APPROVAL_COLUMNS}
      `).bind(
        targetStatus, reason, decisionToken, approvalId, userId, conversationId, conversationId, userId,
      ),
      this.db.prepare(`
        UPDATE agent_tool_invocation
        SET status = ?, started_at = CASE WHEN ? = 1
              THEN strftime('%Y-%m-%dT%H:%M:%fZ', 'now') ELSE NULL END,
            completed_at = NULL, error_message = NULL,
            updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
        WHERE invocation_id = (
          SELECT invocation_id FROM agent_approval
          WHERE id = ? AND decision_token = ? AND status = ?
        ) AND status = 'WAITING_APPROVAL'
        RETURNING invocation_id AS invocationId, arguments_json AS argumentsJson
      `).bind(invocationStatus, approved ? 1 : 0,
        approvalId, decisionToken, targetStatus),
      this.db.prepare(`
        UPDATE agent_conversation
        SET status = 'running', cancel_requested = 0,
            updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
        WHERE id = ? AND user_id = ? AND status = 'awaiting_approval'
          AND run_version = (SELECT run_version FROM agent_approval WHERE id = ? AND decision_token = ?)
        RETURNING id
      `).bind(conversationId, userId, approvalId, decisionToken),
      this.db.prepare(`
        INSERT INTO agent_event (conversation_id, run_version, event_type, data_content)
        SELECT conversation_id, run_version, ?, json_object(
          'approvalId', id, 'invocationId', invocation_id, 'tool', tool_name
        ) FROM agent_approval
        WHERE id = ? AND decision_token = ? AND status = ?
          AND EXISTS (
            SELECT 1 FROM agent_conversation
            WHERE id = agent_approval.conversation_id AND user_id = agent_approval.user_id
              AND run_version = agent_approval.run_version AND status = 'running'
          )
        RETURNING id
      `).bind(eventType, approvalId, decisionToken, targetStatus),
    ]);

    const changed = results.every((result) => (result?.meta?.changes ?? 0) === 1);
    if (changed) {
      const approval = await this.db.prepare(`
        SELECT ${APPROVAL_COLUMNS} FROM agent_approval WHERE id = ?
      `).bind(approvalId).first<AgentApprovalRecord>();
      const invocation = await this.db.prepare(`
        SELECT invocation_id AS invocationId, arguments_json AS argumentsJson
        FROM agent_tool_invocation WHERE invocation_id = (
          SELECT invocation_id FROM agent_approval WHERE id = ?
        )
      `).bind(approvalId).first<{ invocationId: string; argumentsJson: string }>();
      const event = await this.db.prepare(`
        SELECT id FROM agent_event
        WHERE conversation_id = ? AND run_version = ? AND event_type = ?
          AND json_extract(data_content, '$.invocationId') = (
            SELECT invocation_id FROM agent_approval WHERE id = ?
          )
      `).bind(conversationId, approval?.runVersion ?? 0, eventType, approvalId).first<{ id: number }>();
      if (!approval || !invocation || !event) throw new Error("Agent approval decision could not be completed");
      return { kind: "decided", approval, argumentsJson: invocation.argumentsJson, eventId: event.id };
    }
    const existing = await this.db.prepare(`
      SELECT status FROM agent_approval
      WHERE id = ? AND user_id = ? AND conversation_id = ?
    `).bind(approvalId, userId, conversationId).first<{ status: string }>();
    return existing ? { kind: "conflict" } : { kind: "not_found" };
  }

  async completeInvocation(
    userId: number,
    conversationId: number,
    runVersion: number,
    invocationId: string,
    result: unknown,
  ): Promise<number> {
    const resultJson = JSON.stringify(result);
    const rows = await this.db.batch([
      this.db.prepare(`
        UPDATE agent_tool_invocation
        SET status = 'SUCCEEDED', result_json = ?, error_message = NULL,
            completed_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now'),
            updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
        WHERE invocation_id = ? AND conversation_id = ? AND run_version = ?
          AND status IN ('RUNNING', 'UNKNOWN')
          AND EXISTS (SELECT 1 FROM agent_conversation WHERE id = ? AND user_id = ?)
        RETURNING tool_name
      `).bind(resultJson, invocationId, conversationId, runVersion, conversationId, userId),
      this.db.prepare(`
        INSERT INTO agent_message (conversation_id, run_version, role, kind, tool_name, invocation_id, content)
        SELECT ?, ?, 'tool', 'tool_result', tool_name, invocation_id, ?
        FROM agent_tool_invocation WHERE invocation_id = ? AND status = 'SUCCEEDED'
          AND EXISTS (SELECT 1 FROM agent_conversation WHERE id = ? AND user_id = ?)
      `).bind(conversationId, runVersion, resultJson, invocationId, conversationId, userId),
      this.db.prepare(`
        INSERT INTO agent_event (conversation_id, run_version, event_type, data_content)
        SELECT ?, ?, 'tool_end', json_object('invocationId', ?, 'tool', tool_name, 'result', json(?))
        FROM agent_tool_invocation WHERE invocation_id = ? AND status = 'SUCCEEDED'
        RETURNING id
      `).bind(conversationId, runVersion, invocationId, resultJson, invocationId),
    ]);
    if ((rows[0]?.meta?.changes ?? 0) !== 1 || (rows[1]?.meta?.changes ?? 0) !== 1
      || (rows[2]?.meta?.changes ?? 0) !== 1) throw new Error("Agent tool invocation changed before completion");
    const event = await this.db.prepare(`
      SELECT id FROM agent_event
      WHERE conversation_id = ? AND run_version = ? AND event_type = 'tool_end'
        AND json_extract(data_content, '$.invocationId') = ?
    `).bind(conversationId, runVersion, invocationId).first<{ id: number }>();
    if (!event) throw new Error("Agent tool completion event could not be read");
    return event.id;
  }

  async failInvocation(
    userId: number,
    conversationId: number,
    runVersion: number,
    invocationId: string,
    message: string,
    unknownOutcome: boolean,
  ): Promise<number> {
    const status = unknownOutcome ? "UNKNOWN" : "FAILED";
    const eventType = unknownOutcome ? "tool_unknown" : "tool_error";
    const rows = await this.db.batch([
      this.db.prepare(`
        UPDATE agent_tool_invocation
        SET status = ?, error_message = ?,
            completed_at = CASE WHEN ? = 'FAILED'
              THEN strftime('%Y-%m-%dT%H:%M:%fZ', 'now') ELSE completed_at END,
            updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
        WHERE invocation_id = ? AND conversation_id = ? AND run_version = ?
          AND status IN ('RUNNING', 'UNKNOWN')
          AND EXISTS (SELECT 1 FROM agent_conversation WHERE id = ? AND user_id = ?)
        RETURNING tool_name
      `).bind(status, message, status, invocationId, conversationId, runVersion, conversationId, userId),
      this.db.prepare(`
        INSERT INTO agent_message (conversation_id, run_version, role, kind, tool_name, invocation_id, content)
        SELECT ?, ?, 'tool', 'tool_result', tool_name, invocation_id, ?
        FROM agent_tool_invocation WHERE invocation_id = ? AND status = ?
          AND EXISTS (SELECT 1 FROM agent_conversation WHERE id = ? AND user_id = ?)
      `).bind(
        conversationId, runVersion,
        JSON.stringify({ ok: false, error: message, outcomeUnknown: unknownOutcome }),
        invocationId, status, conversationId, userId,
      ),
      this.db.prepare(`
        INSERT INTO agent_event (conversation_id, run_version, event_type, data_content)
        SELECT ?, ?, ?, json_object('invocationId', ?, 'tool', tool_name, 'error', ?)
        FROM agent_tool_invocation WHERE invocation_id = ? AND status = ?
        RETURNING id
      `).bind(conversationId, runVersion, eventType, invocationId, message, invocationId, status),
    ]);
    if ((rows[0]?.meta?.changes ?? 0) !== 1 || (rows[1]?.meta?.changes ?? 0) !== 1
      || (rows[2]?.meta?.changes ?? 0) !== 1) throw new Error("Agent tool invocation changed before failure was recorded");
    const event = await this.db.prepare(`
      SELECT id FROM agent_event
      WHERE conversation_id = ? AND run_version = ? AND event_type = ?
        AND json_extract(data_content, '$.invocationId') = ?
    `).bind(conversationId, runVersion, eventType, invocationId).first<{ id: number }>();
    if (!event) throw new Error("Agent tool failure event could not be read");
    return event.id;
  }
}
