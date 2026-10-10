import type { D1Binding } from "@dinqor/worker-runtime";
import type { AgentPlanItem, AgentPlanRepository } from "../interfaces/agent-plan-repository.js";

/** Persists the latest plan snapshot together with replayable Agent message and event rows. */
export class D1AgentPlanMapper implements AgentPlanRepository {
  constructor(private readonly db: D1Binding) {}

  async replace(userId: number, conversationId: number, runVersion: number, items: AgentPlanItem[]): Promise<number | null> {
    const planJson = JSON.stringify(items);
    const results = await this.db.batch([
      this.db.prepare(`UPDATE agent_conversation SET plan_json = ?, updated_at =
        strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
        WHERE id = ? AND user_id = ? AND run_version = ? AND status = 'running'
        RETURNING id`).bind(planJson, conversationId, userId, runVersion),
      this.db.prepare(`INSERT INTO agent_message (conversation_id, run_version, role, kind, content)
        SELECT ?, ?, 'assistant', 'plan', ? WHERE EXISTS (
          SELECT 1 FROM agent_conversation WHERE id = ? AND user_id = ? AND run_version = ? AND status = 'running'
        ) RETURNING id`).bind(conversationId, runVersion, planJson, conversationId, userId, runVersion),
      this.db.prepare(`INSERT INTO agent_event (conversation_id, run_version, event_type, data_content)
        SELECT ?, ?, 'plan', ? WHERE EXISTS (
          SELECT 1 FROM agent_conversation WHERE id = ? AND user_id = ? AND run_version = ? AND status = 'running'
        ) RETURNING id`).bind(conversationId, runVersion, planJson, conversationId, userId, runVersion),
    ]);
    if ((results[0]?.meta?.changes ?? 0) !== 1) return null;
    if ((results[1]?.meta?.changes ?? 0) !== 1 || (results[2]?.meta?.changes ?? 0) !== 1) {
      throw new Error("Agent plan could not be persisted");
    }
    const eventId = results[2]?.results?.[0]?.id;
    if (!Number.isSafeInteger(eventId) || Number(eventId) < 1) throw new Error("Agent plan event was not persisted");
    return Number(eventId);
  }
}
