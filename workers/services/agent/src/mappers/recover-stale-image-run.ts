import type { D1Binding } from "@dinqor/worker-runtime";
import type { AgentConversationRecord } from "../interfaces/agent-run-repository.js";

const STALE_MS = 10 * 60_000;
const MESSAGE = "图片生成任务已中断或超时，请重试。";

/** Recover abandoned image runs only; active progress and newer run versions are fenced. */
export async function recoverStaleImageRun(db: D1Binding, row: AgentConversationRecord | null, now = Date.now()): Promise<boolean> {
  if (!row || row.status !== "running" || !(Date.parse(row.updatedAt) < now - STALE_MS)) return false;
  const cutoff = new Date(now - STALE_MS).toISOString();
  const predicate = `id = ? AND user_id = ? AND run_version = ? AND status = 'running'
    AND EXISTS (SELECT 1 FROM agent_event e WHERE e.conversation_id = agent_conversation.id
      AND e.run_version = agent_conversation.run_version AND e.event_type = 'tool_start'
      AND json_valid(e.data_content) AND json_extract(e.data_content, '$.tool') = 'image_generate')
    AND NOT EXISTS (SELECT 1 FROM agent_event e WHERE e.conversation_id = agent_conversation.id
      AND e.run_version = agent_conversation.run_version AND e.event_type <> 'error' AND e.created_at >= ?)`;
  const params = [row.id, row.userId, row.runVersion, cutoff];
  const results = await db.batch([
    db.prepare(`INSERT INTO agent_event (conversation_id, run_version, event_type, data_content)
      SELECT id, run_version, 'error', ? FROM agent_conversation WHERE ${predicate}`)
      .bind(JSON.stringify(MESSAGE), ...params),
    db.prepare(`UPDATE agent_conversation SET status = 'failed', error_message = ?, cancel_requested = 0,
      last_completed_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now'),
      updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now') WHERE ${predicate}`).bind(MESSAGE, ...params),
  ]);
  return results[1].meta.changes === 1;
}
