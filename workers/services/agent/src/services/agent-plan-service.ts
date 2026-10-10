import type { AgentPlanItem, AgentPlanRepository } from "../interfaces/agent-plan-repository.js";

export class AgentPlanError extends Error {
  constructor(readonly status: number, message: string) { super(message); }
}

const STATUSES = new Set(["PENDING", "IN_PROGRESS", "DONE", "DROPPED"]);

function parsePlan(value: unknown): AgentPlanItem[] {
  if (!value || typeof value !== "object" || Array.isArray(value)
    || !Array.isArray((value as Record<string, unknown>).items)) {
    throw new AgentPlanError(400, "计划步骤格式不正确");
  }
  const raw = (value as { items: unknown[] }).items;
  if (raw.length > 20) throw new AgentPlanError(400, "计划最多包含 20 个步骤");
  let inProgress = 0;
  const result = raw.map((item) => {
    if (!item || typeof item !== "object" || Array.isArray(item)) throw new AgentPlanError(400, "计划步骤格式不正确");
    const row = item as Record<string, unknown>;
    const title = typeof row.title === "string" ? row.title.trim() : "";
    const status = typeof row.status === "string" ? row.status : "";
    const note = row.note === undefined || row.note === null ? "" : typeof row.note === "string" ? row.note.trim() : null;
    if (!title || title.length > 120 || !STATUSES.has(status) || note === null || note.length > 300
      || Object.keys(row).some((key) => !["title", "status", "note"].includes(key))) {
      throw new AgentPlanError(400, "计划步骤需要有效标题、状态和备注");
    }
    if (status === "IN_PROGRESS" && ++inProgress > 1) {
      throw new AgentPlanError(400, "同一计划中只能有一个进行中的步骤");
    }
    if (status === "DROPPED" && !note) throw new AgentPlanError(400, "放弃的步骤必须说明原因");
    return { title, status: status as AgentPlanItem["status"], ...(note ? { note } : {}) };
  });
  return result;
}

export class AgentPlanService {
  constructor(private readonly repository: AgentPlanRepository) {}

  async replace(userId: number, conversationId: number, runVersion: number, value: unknown) {
    const items = parsePlan(value);
    const eventId = await this.repository.replace(userId, conversationId, runVersion, items);
    if (eventId === null) throw new AgentPlanError(409, "当前会话已变化，无法更新计划");
    const unfinished = items.filter((item) => item.status === "PENDING" || item.status === "IN_PROGRESS");
    return {
      eventId,
      result: {
        ok: true,
        total: items.length,
        unfinished: unfinished.length,
        items,
        hint: unfinished.length === 0
          ? "计划中所有步骤都已收口，可以给出最终回复。"
          : "计划已保存。现在继续执行下一个未完成步骤。",
      },
    };
  }
}
