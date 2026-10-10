import type { D1Binding } from "@dinqor/worker-runtime";
import type { BlogPlan, BlogPlanRepository, BlogPlanRun, BlogPlanStatus } from "../interfaces/blog-plan-repository.js";

interface PlanRow extends Omit<BlogPlan, "triggerTimes" | "topics" | "autoPublish" | "syncCsdn" | "syncJuejin" | "runOnce"> {
  triggerTimesJson: string;
  topicsJson: string;
  autoPublish: number;
  syncCsdn: number;
  syncJuejin: number;
  runOnce: number;
}

interface RunRow extends Omit<BlogPlanRun, "reviewPass"> {
  reviewPass: number | null;
}

const PLAN_SELECT = `
  SELECT id, user_id AS userId, client_request_id AS clientRequestId, topic, audience, tone,
         schedule_type AS scheduleType, scheduled_date AS scheduledDate, ai_option AS aiOption,
         knowledge_material_id AS knowledgeMaterialId, auto_publish AS autoPublish,
         timezone, trigger_time AS triggerTime, trigger_times_json AS triggerTimesJson,
         topics_json AS topicsJson, next_topic_index AS nextTopicIndex,
         sync_csdn AS syncCsdn, sync_juejin AS syncJuejin, run_once AS runOnce,
         next_run_at AS nextRunAt, last_run_at AS lastRunAt,
         processing_token AS processingToken, claimed_at AS claimedAt, status,
         error_message AS errorMessage, deleted_at AS deletedAt,
         created_at AS createdAt, updated_at AS updatedAt
  FROM blog_content_plan
`;

const RUN_SELECT = `
  SELECT id, plan_id AS planId, scheduled_at AS scheduledAt, trigger_type AS triggerType,
         status, stage_error AS stageError, error_message AS errorMessage,
         agent_task_id AS agentTaskId, local_post_id AS localPostId, review_pass AS reviewPass,
         review_reason AS reviewReason, csdn_status AS csdnStatus,
         csdn_error_code AS csdnErrorCode, csdn_error_message AS csdnErrorMessage,
         csdn_external_id AS csdnExternalId, csdn_url AS csdnUrl,
         juejin_status AS juejinStatus, juejin_error_code AS juejinErrorCode,
         juejin_error_message AS juejinErrorMessage, juejin_external_id AS juejinExternalId,
         juejin_url AS juejinUrl, started_at AS startedAt, finished_at AS finishedAt,
         created_at AS createdAt
  FROM blog_plan_run
`;

function stringArray(value: string): string[] {
  try {
    const parsed: unknown = JSON.parse(value);
    return Array.isArray(parsed) && parsed.every((item) => typeof item === "string") ? parsed : [];
  } catch {
    return [];
  }
}

function mapPlan(row: PlanRow): BlogPlan {
  return {
    ...row,
    triggerTimes: stringArray(row.triggerTimesJson),
    topics: stringArray(row.topicsJson),
    autoPublish: row.autoPublish !== 0,
    syncCsdn: row.syncCsdn !== 0,
    syncJuejin: row.syncJuejin !== 0,
    runOnce: row.runOnce !== 0,
  };
}

function mapRun(row: RunRow): BlogPlanRun {
  return { ...row, reviewPass: row.reviewPass === null ? null : row.reviewPass !== 0 };
}

export class D1BlogPlanMapper implements BlogPlanRepository {
  constructor(private readonly db: D1Binding) {}

  private insertStatement(plan: Omit<BlogPlan, "id" | "createdAt" | "updatedAt">) {
    return this.db.prepare(`
      INSERT INTO blog_content_plan
        (user_id, client_request_id, topic, audience, tone, schedule_type, scheduled_date,
         ai_option, knowledge_material_id, auto_publish, timezone, trigger_time,
         trigger_times_json, topics_json, next_topic_index, sync_csdn, sync_juejin,
         run_once, next_run_at, last_run_at, status, error_message)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      RETURNING id
    `).bind(
      plan.userId, plan.clientRequestId, plan.topic, plan.audience, plan.tone,
      plan.scheduleType, plan.scheduledDate, plan.aiOption, plan.knowledgeMaterialId,
      Number(plan.autoPublish), plan.timezone, plan.triggerTime,
      JSON.stringify(plan.triggerTimes), JSON.stringify(plan.topics), plan.nextTopicIndex,
      Number(plan.syncCsdn), Number(plan.syncJuejin), Number(plan.runOnce),
      plan.nextRunAt, plan.lastRunAt, plan.status, plan.errorMessage,
    );
  }

  async findById(userId: number, planId: number): Promise<BlogPlan | null> {
    const row = await this.db.prepare(
      `${PLAN_SELECT} WHERE id = ? AND user_id = ? AND deleted_at IS NULL`,
    ).bind(planId, userId).first<PlanRow>();
    return row ? mapPlan(row) : null;
  }

  async findByRequestId(userId: number, requestId: string): Promise<BlogPlan | null> {
    const row = await this.db.prepare(
      `${PLAN_SELECT} WHERE user_id = ? AND client_request_id = ?`,
    ).bind(userId, requestId).first<PlanRow>();
    return row ? mapPlan(row) : null;
  }

  async list(userId: number, limit: number, offset: number): Promise<BlogPlan[]> {
    const rows = await this.db.prepare(
      `${PLAN_SELECT} WHERE user_id = ? AND deleted_at IS NULL ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?`,
    ).bind(userId, limit, offset).all<PlanRow>();
    return rows.results.map(mapPlan);
  }

  async count(userId: number): Promise<number> {
    const row = await this.db.prepare(
      "SELECT COUNT(*) AS count FROM blog_content_plan WHERE user_id = ? AND deleted_at IS NULL",
    ).bind(userId).first<{ count: number }>();
    return row?.count ?? 0;
  }

  async countActive(userId: number): Promise<number> {
    const row = await this.db.prepare(`
      SELECT COUNT(*) AS count FROM blog_content_plan
      WHERE user_id = ? AND deleted_at IS NULL AND status IN ('ACTIVE', 'RUNNING', 'PAUSED')
    `).bind(userId).first<{ count: number }>();
    return row?.count ?? 0;
  }

  async countCreatedSince(userId: number, since: string): Promise<number> {
    const row = await this.db.prepare(
      "SELECT COUNT(*) AS count FROM blog_content_plan WHERE user_id = ? AND created_at >= ?",
    ).bind(userId, since).first<{ count: number }>();
    return row?.count ?? 0;
  }

  async create(plan: Omit<BlogPlan, "id" | "createdAt" | "updatedAt">): Promise<number> {
    const row = await this.insertStatement(plan).first<{ id: number }>();
    if (!row || !Number.isSafeInteger(row.id) || row.id < 1) throw new Error("Blog plan was not persisted");
    return row.id;
  }

  async createMany(plans: Array<Omit<BlogPlan, "id" | "createdAt" | "updatedAt">>): Promise<number[]> {
    if (plans.length === 0) return [];
    const results = await this.db.batch(plans.map((plan) => this.insertStatement(plan)));
    if (results.length !== plans.length) throw new Error("Blog plan batch was not fully persisted");
    return results.map((result) => {
      const id = result.results?.[0]?.id;
      if (typeof id !== "number" || !Number.isSafeInteger(id) || id < 1) {
        throw new Error("Blog plan batch did not return its generated IDs");
      }
      return id;
    });
  }

  async claimDue(now: string, limit: number, tokenPrefix: string): Promise<BlogPlan[]> {
    const rows = await this.db.prepare(`
      UPDATE blog_content_plan
      SET status = 'RUNNING', processing_token = ? || id, claimed_at = ?, updated_at = ?
      WHERE id IN (
        SELECT id FROM blog_content_plan
        WHERE status = 'ACTIVE' AND deleted_at IS NULL AND next_run_at IS NOT NULL AND next_run_at <= ?
        ORDER BY next_run_at ASC, id ASC LIMIT ?
      ) AND status = 'ACTIVE' AND deleted_at IS NULL
      RETURNING id, user_id AS userId
    `).bind(`${tokenPrefix}:`, now, now, now, limit).all<{ id: number; userId: number }>();
    return Promise.all(rows.results.map(async ({ id, userId }) => {
      const plan = await this.findById(userId, id);
      if (!plan || !plan.processingToken) throw new Error("Claimed blog plan disappeared");
      return plan;
    }));
  }

  async listStaleClaims(cutoff: string, limit: number): Promise<BlogPlan[]> {
    const rows = await this.db.prepare(`
      ${PLAN_SELECT} WHERE status = 'RUNNING' AND deleted_at IS NULL AND claimed_at <= ?
      ORDER BY claimed_at ASC, id ASC LIMIT ?
    `).bind(cutoff, limit).all<PlanRow>();
    return rows.results.map(mapPlan);
  }

  async hasSucceededRun(planId: number, processingToken: string): Promise<boolean> {
    const row = await this.db.prepare(`
      SELECT 1 AS found FROM blog_plan_run
      WHERE plan_id = ? AND processing_token = ? AND status = 'SUCCEEDED' LIMIT 1
    `).bind(planId, processingToken).first<{ found: number }>();
    return row?.found === 1;
  }

  async recoverStalePlan(input: {
    planId: number; token: string; cutoff: string; status: BlogPlan["status"];
    nextRunAt: string | null; lastRunAt: string; errorMessage: string | null; updatedAt: string;
  }): Promise<boolean> {
    const results = await this.db.batch([
      this.db.prepare(`
        UPDATE blog_plan_run SET status = 'FAILED', stage_error = COALESCE(stage_error, 'generate'),
          error_message = ?, finished_at = ?, updated_at = ?
        WHERE plan_id = ? AND processing_token = ?
          AND status IN ('GENERATING', 'REVIEWING', 'PUBLISHING')
          AND EXISTS (
            SELECT 1 FROM blog_content_plan p WHERE p.id = ? AND p.status = 'RUNNING'
              AND p.processing_token = ? AND p.claimed_at <= ?
          )
      `).bind(input.errorMessage, input.updatedAt, input.updatedAt, input.planId, input.token,
        input.planId, input.token, input.cutoff),
      this.db.prepare(`
        UPDATE blog_content_plan SET status = ?, next_run_at = ?, last_run_at = ?,
          error_message = ?, processing_token = NULL, claimed_at = NULL, updated_at = ?
        WHERE id = ? AND status = 'RUNNING' AND processing_token = ? AND claimed_at <= ?
      `).bind(input.status, input.nextRunAt, input.lastRunAt, input.errorMessage,
        input.updatedAt, input.planId, input.token, input.cutoff),
    ]);
    return results[1]?.meta.changes === 1;
  }

  async failStaleManualRuns(cutoff: string, updatedAt: string, limit: number): Promise<number> {
    const result = await this.db.prepare(`
      UPDATE blog_plan_run SET status = 'FAILED', stage_error = COALESCE(stage_error, 'generate'),
        error_message = '执行进程意外中断，本次手动触发已标记失败', finished_at = ?, updated_at = ?
      WHERE id IN (
        SELECT id FROM blog_plan_run
        WHERE trigger_type = 'MANUAL' AND status IN ('GENERATING', 'REVIEWING', 'PUBLISHING')
          AND claimed_at <= ?
        ORDER BY claimed_at ASC, id ASC LIMIT ?
      )
    `).bind(updatedAt, updatedAt, cutoff, limit).run();
    return result.meta.changes;
  }

  async createScheduledRun(plan: BlogPlan, scheduledAt: string, token: string): Promise<number | null> {
    const row = await this.db.prepare(`
      INSERT INTO blog_plan_run (
        plan_id, scheduled_at, trigger_type, status, csdn_status, juejin_status,
        processing_token, claimed_at, started_at
      ) VALUES (?, ?, 'SCHEDULED', 'GENERATING', ?, ?, ?, ?, ?)
      ON CONFLICT(plan_id, scheduled_at) DO NOTHING
      RETURNING id
    `).bind(
      plan.id, scheduledAt,
      plan.syncCsdn ? "PENDING" : "UNSET",
      plan.syncJuejin ? "PENDING" : "UNSET",
      token, scheduledAt, scheduledAt,
    ).first<{ id: number }>();
    return row?.id ?? null;
  }

  async createManualRun(userId: number, planId: number, scheduledAt: string, token: string): Promise<number | null> {
    const row = await this.db.prepare(`
      INSERT INTO blog_plan_run (
        plan_id, scheduled_at, trigger_type, status, csdn_status, juejin_status,
        processing_token, claimed_at, started_at
      )
      SELECT p.id, ?, 'MANUAL', 'GENERATING',
             CASE WHEN p.sync_csdn = 1 THEN 'PENDING' ELSE 'UNSET' END,
             CASE WHEN p.sync_juejin = 1 THEN 'PENDING' ELSE 'UNSET' END,
             ?, ?, ?
      FROM blog_content_plan p
      WHERE p.id = ? AND p.user_id = ? AND p.deleted_at IS NULL
        AND p.status != 'RUNNING' AND p.run_once = 0
        AND NOT EXISTS (
          SELECT 1 FROM blog_plan_run r WHERE r.plan_id = p.id
            AND r.status IN ('GENERATING', 'REVIEWING', 'PUBLISHING')
        )
      ON CONFLICT(plan_id, scheduled_at) DO NOTHING
      RETURNING id
    `).bind(scheduledAt, token, scheduledAt, scheduledAt, planId, userId).first<{ id: number }>();
    return row?.id ?? null;
  }

  async consumeNextTopic(planId: number): Promise<string | null> {
    const row = await this.db.prepare(`
      UPDATE blog_content_plan SET next_topic_index = next_topic_index + 1
      WHERE id = ? AND deleted_at IS NULL AND topics_json != '[]'
        AND status IN ('ACTIVE', 'RUNNING', 'PAUSED', 'FAILED')
      RETURNING topics_json AS topicsJson, next_topic_index AS nextTopicIndex
    `).bind(planId).first<{ topicsJson: string; nextTopicIndex: number }>();
    if (!row) return null;
    const topics = stringArray(row.topicsJson);
    if (topics.length === 0) return null;
    const index = Math.max(0, row.nextTopicIndex - 1) % topics.length;
    return topics[index] ?? null;
  }

  async updateRun(planId: number, runId: number, patch: Partial<BlogPlanRun>): Promise<boolean> {
    const columns: Record<string, string> = {
      status: "status", stageError: "stage_error", errorMessage: "error_message",
      agentTaskId: "agent_task_id", localPostId: "local_post_id", reviewPass: "review_pass",
      reviewReason: "review_reason", csdnStatus: "csdn_status", csdnErrorCode: "csdn_error_code",
      csdnErrorMessage: "csdn_error_message", csdnExternalId: "csdn_external_id", csdnUrl: "csdn_url",
      juejinStatus: "juejin_status", juejinErrorCode: "juejin_error_code",
      juejinErrorMessage: "juejin_error_message", juejinExternalId: "juejin_external_id",
      juejinUrl: "juejin_url", startedAt: "started_at", finishedAt: "finished_at",
    };
    const assignments: string[] = [];
    const values: Array<string | number | null> = [];
    for (const [key, column] of Object.entries(columns)) {
      if (!Object.hasOwn(patch, key)) continue;
      const value = patch[key as keyof BlogPlanRun];
      assignments.push(`${column} = ?`);
      values.push(typeof value === "boolean" ? Number(value) : value as string | number | null);
    }
    if (assignments.length === 0) return false;
    assignments.push("updated_at = ?");
    values.push(new Date().toISOString(), planId, runId);
    const result = await this.db.prepare(`
      UPDATE blog_plan_run SET ${assignments.join(", ")} WHERE plan_id = ? AND id = ?
    `).bind(...values).run();
    return result.meta.changes === 1;
  }

  async finishScheduledPlan(input: {
    planId: number; token: string; status: BlogPlan["status"]; nextRunAt: string | null;
    lastRunAt: string; errorMessage: string | null; updatedAt: string;
  }): Promise<boolean> {
    const result = await this.db.prepare(`
      UPDATE blog_content_plan SET status = ?, next_run_at = ?, last_run_at = ?,
        error_message = ?, processing_token = NULL, claimed_at = NULL, updated_at = ?
      WHERE id = ? AND status = 'RUNNING' AND processing_token = ? AND deleted_at IS NULL
    `).bind(
      input.status, input.nextRunAt, input.lastRunAt, input.errorMessage,
      input.updatedAt, input.planId, input.token,
    ).run();
    return result.meta.changes === 1;
  }

  async update(plan: BlogPlan, expectedStatus: BlogPlanStatus): Promise<boolean> {
    const result = await this.db.prepare(`
      UPDATE blog_content_plan SET topic = ?, audience = ?, tone = ?, schedule_type = ?,
        scheduled_date = ?, ai_option = ?, knowledge_material_id = ?, auto_publish = ?,
        timezone = ?, trigger_time = ?, trigger_times_json = ?, topics_json = ?,
        next_topic_index = ?, sync_csdn = ?, sync_juejin = ?, run_once = ?,
        next_run_at = ?, updated_at = ?
      WHERE id = ? AND user_id = ? AND status = ? AND deleted_at IS NULL
    `).bind(
      plan.topic, plan.audience, plan.tone, plan.scheduleType, plan.scheduledDate,
      plan.aiOption, plan.knowledgeMaterialId, Number(plan.autoPublish), plan.timezone,
      plan.triggerTime, JSON.stringify(plan.triggerTimes), JSON.stringify(plan.topics),
      plan.nextTopicIndex, Number(plan.syncCsdn), Number(plan.syncJuejin), Number(plan.runOnce),
      plan.nextRunAt, new Date().toISOString(), plan.id, plan.userId, expectedStatus,
    ).run();
    return result.meta.changes === 1;
  }

  async setStatus(
    userId: number,
    planId: number,
    from: BlogPlanStatus[],
    status: BlogPlanStatus,
    values: { nextRunAt?: string | null; errorMessage?: string | null } = {},
  ): Promise<boolean> {
    const assignments = ["status = ?", "updated_at = ?"];
    const bound: (string | number | null)[] = [status, new Date().toISOString()];
    if (Object.hasOwn(values, "nextRunAt")) { assignments.push("next_run_at = ?"); bound.push(values.nextRunAt ?? null); }
    if (Object.hasOwn(values, "errorMessage")) { assignments.push("error_message = ?"); bound.push(values.errorMessage ?? null); }
    bound.push(planId, userId, ...from);
    const result = await this.db.prepare(`
      UPDATE blog_content_plan SET ${assignments.join(", ")}
      WHERE id = ? AND user_id = ? AND deleted_at IS NULL AND status IN (${from.map(() => "?").join(",")})
    `).bind(...bound).run();
    return result.meta.changes === 1;
  }

  async softDelete(userId: number, planId: number, deletedAt: string): Promise<boolean> {
    const result = await this.db.prepare(`
      UPDATE blog_content_plan SET deleted_at = ?, updated_at = ?
      WHERE id = ? AND user_id = ? AND deleted_at IS NULL
    `).bind(deletedAt, deletedAt, planId, userId).run();
    return result.meta.changes === 1;
  }

  async listRuns(planId: number, limit: number, offset: number): Promise<BlogPlanRun[]> {
    const rows = await this.db.prepare(
      `${RUN_SELECT} WHERE plan_id = ? ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?`,
    ).bind(planId, limit, offset).all<RunRow>();
    return rows.results.map(mapRun);
  }

  async countRuns(planId: number): Promise<number> {
    const row = await this.db.prepare("SELECT COUNT(*) AS count FROM blog_plan_run WHERE plan_id = ?")
      .bind(planId).first<{ count: number }>();
    return row?.count ?? 0;
  }

  async findRun(planId: number, runId: number): Promise<BlogPlanRun | null> {
    const row = await this.db.prepare(`${RUN_SELECT} WHERE plan_id = ? AND id = ?`)
      .bind(planId, runId).first<RunRow>();
    return row ? mapRun(row) : null;
  }
}
