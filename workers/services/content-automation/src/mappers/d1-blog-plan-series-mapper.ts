import type { D1Binding } from "@dinqor/worker-runtime";
import type { BlogPlan } from "../interfaces/blog-plan-repository.js";
import type {
  BlogPlanSeries,
  BlogPlanSeriesRepository,
} from "../interfaces/blog-plan-series-repository.js";

type SeriesRow = Omit<BlogPlanSeries, "topics" | "platforms"> & {
  topicsJson: string;
  platformsJson: string;
};
const SELECT = `SELECT id, user_id AS userId, client_request_id AS clientRequestId, title, topic, topics_json AS topicsJson,
  audience, tone, ai_option AS aiOption, start_date AS startDate, trigger_time AS triggerTime,
  timezone, platforms_json AS platformsJson, publish_mode AS publishMode, status,
  created_at AS createdAt, updated_at AS updatedAt FROM blog_plan_series`;
const parseArray = <T extends string>(value: string): T[] => {
  try {
    const result: unknown = JSON.parse(value);
    return Array.isArray(result) ? (result as T[]) : [];
  } catch {
    return [];
  }
};
const map = (row: SeriesRow): BlogPlanSeries => ({
  ...row,
  topics: parseArray<string>(row.topicsJson),
  platforms: parseArray<"local" | "csdn" | "juejin">(row.platformsJson),
});

export class D1BlogPlanSeriesMapper implements BlogPlanSeriesRepository {
  constructor(private readonly db: D1Binding) {}

  async create(
    input: Omit<BlogPlanSeries, "id" | "createdAt" | "updatedAt">,
  ): Promise<number> {
    if (input.clientRequestId) {
      await this.db
        .prepare(
          `INSERT INTO blog_plan_series
      (user_id, client_request_id, title, topic, topics_json, audience, tone, ai_option, start_date,
       trigger_time, timezone, platforms_json, publish_mode, status)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT')
      ON CONFLICT DO NOTHING`,
        )
        .bind(
          input.userId,
          input.clientRequestId,
          input.title,
          input.topic,
          JSON.stringify(input.topics),
          input.audience,
          input.tone,
          input.aiOption,
          input.startDate,
          input.triggerTime,
          input.timezone,
          JSON.stringify(input.platforms),
          input.publishMode,
        )
        .run();
      const existing = await this.findByClientRequestId(
        input.userId,
        input.clientRequestId,
      );
      if (!existing) throw new Error("Series draft was not persisted");
      return existing.id;
    }
    const row = await this.db
      .prepare(
        `INSERT INTO blog_plan_series
      (user_id, title, topic, topics_json, audience, tone, ai_option, start_date,
       trigger_time, timezone, platforms_json, publish_mode, status)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT') RETURNING id`,
      )
      .bind(
        input.userId,
        input.title,
        input.topic,
        JSON.stringify(input.topics),
        input.audience,
        input.tone,
        input.aiOption,
        input.startDate,
        input.triggerTime,
        input.timezone,
        JSON.stringify(input.platforms),
        input.publishMode,
      )
      .first<{ id: number }>();
    if (!row) throw new Error("Series draft was not persisted");
    return row.id;
  }

  async find(userId: number, seriesId: number): Promise<BlogPlanSeries | null> {
    const row = await this.db
      .prepare(`${SELECT} WHERE id = ? AND user_id = ?`)
      .bind(seriesId, userId)
      .first<SeriesRow>();
    if (!row) return null;
    const series = map(row);
    const counts = await this.db
      .prepare(
        `SELECT COUNT(*) AS totalItems,
      SUM(CASE WHEN status = 'FINISHED' THEN 1 ELSE 0 END) AS finishedItems,
      SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS failedItems
      FROM blog_content_plan WHERE plan_series_id = ? AND deleted_at IS NULL`,
      )
      .bind(seriesId)
      .first<{
        totalItems: number;
        finishedItems: number;
        failedItems: number;
      }>();
    return {
      ...series,
      totalItems: counts?.totalItems ?? 0,
      finishedItems: counts?.finishedItems ?? 0,
      failedItems: counts?.failedItems ?? 0,
    };
  }

  async findByClientRequestId(
    userId: number,
    clientRequestId: string,
  ): Promise<BlogPlanSeries | null> {
    const row = await this.db
      .prepare(`${SELECT} WHERE user_id = ? AND client_request_id = ?`)
      .bind(userId, clientRequestId)
      .first<SeriesRow>();
    return row ? this.find(userId, row.id) : null;
  }

  async list(userId: number): Promise<BlogPlanSeries[]> {
    const rows = await this.db
      .prepare(
        `${SELECT} WHERE user_id = ? ORDER BY updated_at DESC, id DESC LIMIT 100`,
      )
      .bind(userId)
      .all<SeriesRow>();
    return Promise.all(
      rows.results.map(
        (row) => this.find(userId, row.id) as Promise<BlogPlanSeries>,
      ),
    );
  }

  async listPlans(userId: number, seriesId: number): Promise<BlogPlan[]> {
    const rows = await this.db
      .prepare(
        `SELECT id, user_id AS userId, client_request_id AS clientRequestId,
      plan_series_id AS planSeriesId, series_day AS seriesDay, topic, audience, tone,
      schedule_type AS scheduleType, scheduled_date AS scheduledDate, ai_option AS aiOption,
      knowledge_material_id AS knowledgeMaterialId, auto_publish AS autoPublish, timezone,
      trigger_time AS triggerTime, trigger_times_json AS triggerTimesJson, topics_json AS topicsJson,
      next_topic_index AS nextTopicIndex, sync_csdn AS syncCsdn, sync_juejin AS syncJuejin,
      run_once AS runOnce, next_run_at AS nextRunAt, last_run_at AS lastRunAt,
      processing_token AS processingToken, claimed_at AS claimedAt, status,
      error_message AS errorMessage, deleted_at AS deletedAt, created_at AS createdAt,
      updated_at AS updatedAt FROM blog_content_plan WHERE user_id = ? AND plan_series_id = ?
      AND deleted_at IS NULL ORDER BY series_day ASC`,
      )
      .bind(userId, seriesId)
      .all<Record<string, unknown>>();
    return rows.results.map((row) => ({
      ...row,
      triggerTimes: parseArray<string>(String(row.triggerTimesJson ?? "[]")),
      topics: parseArray<string>(String(row.topicsJson ?? "[]")),
      autoPublish: row.autoPublish !== 0,
      syncCsdn: row.syncCsdn !== 0,
      syncJuejin: row.syncJuejin !== 0,
      runOnce: row.runOnce !== 0,
    })) as unknown as BlogPlan[];
  }

  async updateDraft(input: BlogPlanSeries): Promise<boolean> {
    const result = await this.db
      .prepare(
        `UPDATE blog_plan_series SET title = ?, topic = ?, topics_json = ?,
      audience = ?, tone = ?, ai_option = ?, start_date = ?, trigger_time = ?, timezone = ?,
      platforms_json = ?, publish_mode = ?, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
      WHERE id = ? AND user_id = ? AND status = 'DRAFT'`,
      )
      .bind(
        input.title,
        input.topic,
        JSON.stringify(input.topics),
        input.audience,
        input.tone,
        input.aiOption,
        input.startDate,
        input.triggerTime,
        input.timezone,
        JSON.stringify(input.platforms),
        input.publishMode,
        input.id,
        input.userId,
      )
      .run();
    return result.meta.changes === 1;
  }

  async activate(
    userId: number,
    seriesId: number,
    plans: Array<Omit<BlogPlan, "id" | "createdAt" | "updatedAt">>,
  ): Promise<boolean> {
    const statements = plans.map((plan) =>
      this.db
        .prepare(
          `INSERT INTO blog_content_plan
      (user_id, client_request_id, topic, audience, tone, schedule_type, scheduled_date, ai_option,
       knowledge_material_id, auto_publish, timezone, trigger_time, trigger_times_json, topics_json,
       next_topic_index, sync_csdn, sync_juejin, run_once, next_run_at, last_run_at, status,
       error_message, plan_series_id, series_day)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id`,
        )
        .bind(
          plan.userId,
          plan.clientRequestId,
          plan.topic,
          plan.audience,
          plan.tone,
          plan.scheduleType,
          plan.scheduledDate,
          plan.aiOption,
          plan.knowledgeMaterialId,
          Number(plan.autoPublish),
          plan.timezone,
          plan.triggerTime,
          JSON.stringify(plan.triggerTimes),
          JSON.stringify(plan.topics),
          plan.nextTopicIndex,
          Number(plan.syncCsdn),
          Number(plan.syncJuejin),
          Number(plan.runOnce),
          plan.nextRunAt,
          plan.lastRunAt,
          plan.status,
          plan.errorMessage,
          seriesId,
          plan.seriesDay ?? null,
        ),
    );
    statements.push(
      this.db
        .prepare(
          `UPDATE blog_plan_series SET status = 'ACTIVE',
      updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now') WHERE id = ? AND user_id = ? AND status = 'DRAFT'`,
        )
        .bind(seriesId, userId),
    );
    const results = await this.db.batch(statements);
    return results.at(-1)?.meta.changes === 1;
  }

  async control(
    userId: number,
    seriesId: number,
    action: "PAUSE" | "RESUME" | "CANCEL" | "RETRY_FAILED",
    retryAt?: string,
  ): Promise<boolean> {
    const retryFailed = action === "RETRY_FAILED";
    const from =
      action === "PAUSE" ? "ACTIVE" : action === "RESUME" ? "PAUSED" : "ACTIVE";
    const to =
      action === "PAUSE"
        ? "PAUSED"
        : action === "CANCEL"
          ? "CANCELLED"
          : "ACTIVE";
    const seriesFrom =
      action === "CANCEL"
        ? ["ACTIVE", "PAUSED", "FAILED"]
        : retryFailed
          ? ["ACTIVE", "FAILED"]
          : [from];
    const childFrom =
      action === "PAUSE"
        ? ["ACTIVE"]
        : action === "RESUME"
          ? ["PAUSED"]
          : action === "CANCEL"
            ? ["ACTIVE", "PAUSED", "FAILED"]
            : ["FAILED"];
    const childTo = to;
    const assignments = retryFailed
      ? "status = ?, next_run_at = ?, error_message = NULL, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')"
      : "status = ?, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')";
    const childBind = retryFailed
      ? [
          childTo,
          retryAt ?? new Date().toISOString(),
          seriesId,
          userId,
          ...childFrom,
        ]
      : [childTo, seriesId, userId, ...childFrom];
    const updateChildren = this.db
      .prepare(
        `UPDATE blog_content_plan SET ${assignments}
      WHERE plan_series_id = ? AND user_id = ?
      AND status IN (${childFrom.map(() => "?").join(",")})
      AND EXISTS (SELECT 1 FROM blog_plan_series WHERE id = ? AND user_id = ? AND status IN (${seriesFrom.map(() => "?").join(",")}))`,
      )
      .bind(...childBind, seriesId, userId, ...seriesFrom);
    const updateSeries = this.db
      .prepare(
        `UPDATE blog_plan_series SET status = ?,
      updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now') WHERE id = ? AND user_id = ? AND status IN (${seriesFrom.map(() => "?").join(",")})`,
      )
      .bind(to, seriesId, userId, ...seriesFrom);
    const results = await this.db.batch([updateChildren, updateSeries]);
    const changed = results[1]?.meta.changes === 1;
    if (changed && action === "RESUME") {
      await this.db
        .prepare(
          `UPDATE blog_plan_series SET status = CASE WHEN EXISTS (
          SELECT 1 FROM blog_content_plan WHERE plan_series_id = ? AND status = 'FAILED'
        ) THEN 'FAILED' ELSE 'FINISHED' END,
        updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
        WHERE id = ? AND status = 'ACTIVE'
        AND NOT EXISTS (SELECT 1 FROM blog_content_plan WHERE plan_series_id = ?
          AND status NOT IN ('FINISHED', 'FAILED', 'CANCELLED'))`,
        )
        .bind(seriesId, seriesId, seriesId)
        .run();
    }
    return changed;
  }
}
