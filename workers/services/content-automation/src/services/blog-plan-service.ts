import type {
  BlogPlan,
  BlogPlanRepository,
  BlogPlanRun,
  BlogPlanScheduleType,
  BlogPlanStatus,
} from "../interfaces/blog-plan-repository.js";
import type { BlogPlanMaterialPort } from "../interfaces/blog-plan-material-port.js";
import type { BlogPlanTopicInput, BlogPlanTopicPort } from "../interfaces/blog-plan-topic-port.js";

export class BlogPlanError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
    this.name = "BlogPlanError";
  }
}

type PlanInput = Record<string, unknown>;
type TimeParts = { year: number; month: number; day: number; hour: number; minute: number; second: number };
const TIME_PATTERN = /^([01]\d|2[0-3]):[0-5]\d$/;
const SCHEDULES = new Set<BlogPlanScheduleType>(["DAILY", "WEEKLY", "ONCE"]);
const AI_OPTIONS = new Set(["DEEP", "PRACTICAL", "NEWS", "OPINION"]);
const ACTIVE_STATUSES: BlogPlanStatus[] = ["ACTIVE", "RUNNING", "PAUSED"];
const PLAN_FIELDS = [
  "id", "topic", "triggerTimes", "topics", "nextTopicIndex", "audience", "tone",
  "scheduleType", "scheduledDate", "aiOption", "knowledgeMaterialId", "autoPublish",
  "timezone", "triggerTime", "syncCsdn", "syncJuejin", "runOnce", "nextRunAt",
  "lastRunAt", "status", "errorMessage", "createdAt", "updatedAt",
] as const;

function view(plan: BlogPlan) {
  return Object.fromEntries(PLAN_FIELDS.map((key) => [key, plan[key]]));
}

function jsonArray(value: unknown, field: string, max: number, maxLength: number): string[] {
  if (value === undefined || value === null) return [];
  if (!Array.isArray(value) || value.length > max
    || value.some((item) => typeof item !== "string" || item.length > maxLength)) {
    throw new BlogPlanError(400, `${field}格式不正确`);
  }
  return [...new Set(value.map((item) => (item as string).trim()).filter(Boolean))];
}

function normalizeTimes(body: PlanInput, fallback?: string[]): string[] {
  let values: unknown = body.triggerTimes;
  if (Array.isArray(values) && values.length === 0 && typeof body.triggerTime === "string" && body.triggerTime.trim()) {
    values = [body.triggerTime];
  }
  if (values === undefined && body.triggerTime !== undefined) values = [body.triggerTime];
  if (values === undefined && fallback) values = fallback;
  if (!Array.isArray(values) || values.length < 1 || values.length > 5
    || values.some((value) => typeof value !== "string" || !TIME_PATTERN.test(value))) {
    throw new BlogPlanError(400, "每天最多设置5个发送时间，格式应为 HH:mm");
  }
  return [...new Set(values as string[])].sort();
}

function timezone(value: unknown, fallback = "Asia/Shanghai"): string {
  if (value === undefined || value === null || value === "") return fallback;
  if (typeof value !== "string" || value.length > 64) throw new BlogPlanError(400, "时区无效");
  try {
    new Intl.DateTimeFormat("en", { timeZone: value }).format();
    return value;
  } catch {
    throw new BlogPlanError(400, `时区无效：${value}`);
  }
}

function date(value: unknown, fallback: string | null = null): string | null {
  if (value === undefined) return fallback;
  if (value === null || value === "") return null;
  if (typeof value !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    throw new BlogPlanError(400, "计划日期格式应为 YYYY-MM-DD");
  }
  const parsed = new Date(`${value}T00:00:00.000Z`);
  if (!Number.isFinite(parsed.getTime()) || parsed.toISOString().slice(0, 10) !== value) {
    throw new BlogPlanError(400, "计划日期无效");
  }
  return value;
}

function localParts(instant: number, zone: string): TimeParts {
  const parts = new Intl.DateTimeFormat("en-GB", {
    timeZone: zone,
    year: "numeric", month: "2-digit", day: "2-digit",
    hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23",
  }).formatToParts(instant);
  const values = Object.fromEntries(parts.map((part) => [part.type, part.value]));
  return {
    year: Number(values.year), month: Number(values.month), day: Number(values.day),
    hour: Number(values.hour), minute: Number(values.minute), second: Number(values.second),
  };
}

function wallClockMs(parts: TimeParts): number {
  return Date.UTC(parts.year, parts.month - 1, parts.day, parts.hour, parts.minute, parts.second);
}

function localInstant(dateValue: string, timeValue: string, zone: string): number {
  const [year, month, day] = dateValue.split("-").map(Number);
  const [hour, minute] = timeValue.split(":").map(Number);
  const wall = Date.UTC(year, month - 1, day, hour, minute);
  const firstOffset = wallClockMs(localParts(wall, zone)) - wall;
  const guess = wall - firstOffset;
  const exactCandidates = [guess - 3_600_000, guess, guess + 3_600_000].filter((candidate) => {
    const actual = localParts(candidate, zone);
    return actual.year === year && actual.month === month && actual.day === day
      && actual.hour === hour && actual.minute === minute;
  });
  if (exactCandidates.length) return Math.min(...exactCandidates);
  // ZonedDateTime shifts forward through a spring DST gap; the first estimate has the same behavior.
  return guess;
}

export function nextOccurrence(plan: Pick<BlogPlan, "scheduleType" | "scheduledDate" | "timezone" | "triggerTime" | "triggerTimes">, reference: number): number {
  const zone = plan.timezone;
  const times = plan.triggerTimes.length ? plan.triggerTimes : [plan.triggerTime];
  const current = localParts(reference, zone);
  const currentDate = new Date(Date.UTC(current.year, current.month - 1, current.day));
  const firstDate = plan.scheduleType === "ONCE" ? plan.scheduledDate : null;
  if (plan.scheduleType === "ONCE" && !firstDate) throw new BlogPlanError(400, "一次性计划必须设置执行日期");
  if (plan.scheduleType === "WEEKLY" && !plan.scheduledDate) throw new BlogPlanError(400, "每周计划必须设置执行日期");
  const targetWeekday = plan.scheduleType === "WEEKLY" && plan.scheduledDate
    ? new Date(`${plan.scheduledDate}T00:00:00Z`).getUTCDay() : null;
  const candidates: number[] = [];
  for (let offset = 0; offset <= 8; offset += 1) {
    const day = new Date(currentDate.getTime() + offset * 86_400_000);
    const candidateDate = `${day.getUTCFullYear().toString().padStart(4, "0")}-${String(day.getUTCMonth() + 1).padStart(2, "0")}-${String(day.getUTCDate()).padStart(2, "0")}`;
    if (firstDate && candidateDate !== firstDate) continue;
    if (targetWeekday !== null && day.getUTCDay() !== targetWeekday) continue;
    for (const time of times) {
      const candidate = localInstant(candidateDate, time, zone);
      if (candidate > reference) candidates.push(candidate);
    }
    if (firstDate) break;
  }
  if (!candidates.length) {
    if (plan.scheduleType === "ONCE") throw new BlogPlanError(400, "一次性计划的执行时间必须晚于当前时间");
    throw new BlogPlanError(400, "无法计算计划的下次执行时间");
  }
  return Math.min(...candidates);
}

function normalizedText(value: unknown, field: string, max: number, fallback = ""): string {
  if (value === undefined || value === null) return fallback;
  if (typeof value !== "string" || value.trim().length > max) throw new BlogPlanError(400, `${field}长度无效`);
  return value.trim();
}

function normalizedTopic(value: unknown): string {
  const topic = normalizedText(value, "文章主题", 500);
  if (!topic) throw new BlogPlanError(400, "请输入文章主题");
  return topic;
}

function boolean(value: unknown, fallback: boolean): boolean {
  if (value === undefined || value === null) return fallback;
  if (typeof value !== "boolean") throw new BlogPlanError(400, "计划开关值无效");
  return value;
}

function countParam(value: number, fallback: number, max: number): number {
  return Number.isSafeInteger(value) && value > 0 ? Math.min(value, max) : fallback;
}

function planInput(userId: number, body: PlanInput, now: number): Omit<BlogPlan, "id" | "createdAt" | "updatedAt"> {
  const scheduleType = (body.scheduleType ?? "DAILY") as BlogPlanScheduleType;
  if (!SCHEDULES.has(scheduleType)) throw new BlogPlanError(400, "计划类型只支持 DAILY / WEEKLY / ONCE");
  const scheduledDate = date(body.scheduledDate);
  if (scheduleType !== "DAILY" && !scheduledDate) throw new BlogPlanError(400, "一次性和每周计划必须设置执行日期");
  const zone = timezone(body.timezone);
  const times = normalizeTimes(body);
  const aiOption = (body.aiOption ?? "DEEP") as BlogPlan["aiOption"];
  if (!AI_OPTIONS.has(aiOption)) throw new BlogPlanError(400, "不支持的 AI 规划方向");
  const requestId = body.clientRequestId;
  if (requestId !== undefined && requestId !== null
    && (typeof requestId !== "string" || requestId.length > 64 || !/^[A-Za-z0-9._:-]+$/.test(requestId))) {
    throw new BlogPlanError(400, "幂等请求标识格式不正确");
  }
  const knowledgeMaterialId = body.knowledgeMaterialId === undefined || body.knowledgeMaterialId === null
    ? null : (typeof body.knowledgeMaterialId === "number" && Number.isSafeInteger(body.knowledgeMaterialId)
      && body.knowledgeMaterialId > 0 ? body.knowledgeMaterialId : NaN);
  if (Number.isNaN(knowledgeMaterialId)) throw new BlogPlanError(400, "知识材料 ID 无效");
  const topics = jsonArray(body.topics, "主题队列", 30, 500);
  const base = {
    userId,
    clientRequestId: typeof requestId === "string" && requestId.trim() ? requestId.trim() : null,
    topic: normalizedTopic(body.topic),
    audience: normalizedText(body.audience, "目标读者", 120),
    tone: normalizedText(body.tone, "文风语气", 60),
    scheduleType,
    scheduledDate,
    aiOption,
    knowledgeMaterialId,
    autoPublish: boolean(body.autoPublish, true),
    timezone: zone,
    triggerTime: times[0],
    triggerTimes: times,
    topics,
    nextTopicIndex: 0,
    syncCsdn: boolean(body.syncCsdn, false),
    syncJuejin: boolean(body.syncJuejin, false),
    runOnce: scheduleType === "ONCE",
    nextRunAt: "",
    lastRunAt: null,
    status: "ACTIVE" as const,
    errorMessage: null,
    deletedAt: null,
  };
  return { ...base, nextRunAt: new Date(nextOccurrence(base, now)).toISOString() };
}

export class BlogPlanService {
  private readonly now: () => number;

  constructor(
    private readonly repository: BlogPlanRepository,
    private readonly options: {
      now?: () => number;
      materials?: BlogPlanMaterialPort;
      topics?: BlogPlanTopicPort;
    } = {},
  ) {
    this.now = options.now ?? Date.now;
  }

  async generateTopics(userId: number, body: PlanInput): Promise<string[]> {
    if (!this.options.topics) throw new BlogPlanError(503, "博客选题服务尚未配置");
    const seed = normalizedTopic(body.topic);
    const requestedCount = body.count === undefined ? 7 : body.count;
    if (typeof requestedCount !== "number" || !Number.isSafeInteger(requestedCount)
      || requestedCount < 1 || requestedCount > 30) {
      throw new BlogPlanError(400, "主题数量需在 1-30 之间");
    }
    const audience = normalizedText(body.audience, "目标读者", 120);
    const tone = normalizedText(body.tone, "文风语气", 60);
    const requestedExclusions = jsonArray(body.excludeTitles, "排除标题", 200, 500);
    const existing = await this.repository.list(userId, 50, 0);
    const excludeTitles = [...new Set([
      seed,
      ...requestedExclusions,
      ...existing.flatMap((plan) => [plan.topic, ...plan.topics]),
    ])];
    let topics: string[];
    try {
      topics = await this.options.topics.generateTopics(userId, {
        seed, count: requestedCount, audience, tone, excludeTitles,
      } satisfies BlogPlanTopicInput);
    } catch {
      throw new BlogPlanError(503, "博客选题服务暂不可用");
    }
    const seen = new Set(excludeTitles.map((title) => title.toLocaleLowerCase()));
    const generated: string[] = [];
    for (const value of topics) {
      if (typeof value !== "string") continue;
      const title = value.trim();
      const key = title.toLocaleLowerCase();
      if (!title || title.length > 500 || seen.has(key)) continue;
      seen.add(key);
      generated.push(title);
      if (generated.length === requestedCount) break;
    }
    return generated;
  }

  async aiGenerate(userId: number, body: PlanInput) {
    const seed = normalizedTopic(body.topic);
    const days = body.days === undefined || body.days === null ? 7 : body.days;
    if (typeof days !== "number" || !Number.isSafeInteger(days) || days < 1 || days > 30) {
      throw new BlogPlanError(400, "计划天数需在 1-30 之间");
    }
    const zone = timezone(body.timezone);
    const timeValue = body.time === undefined || body.time === null || body.time === ""
      ? "09:00" : body.time;
    if (typeof timeValue !== "string" || !TIME_PATTERN.test(timeValue)) {
      throw new BlogPlanError(400, "触发时间格式应为 HH:mm");
    }
    const audience = normalizedText(body.audience, "目标读者", 120);
    const tone = normalizedText(body.tone, "文风语气", 60);
    const syncCsdn = boolean(body.syncCsdn, false);
    const syncJuejin = boolean(body.syncJuejin, false);
    if (!this.options.topics) throw new BlogPlanError(503, "博客选题服务尚未配置");
    const now = this.now();
    const existing = await this.repository.list(userId, 50, 0);
    const excludeTitles = [...new Set([
      seed,
      ...existing.flatMap((plan) => [plan.topic, ...plan.topics]),
    ])];
    let topics: string[];
    try {
      topics = await this.options.topics.generateTopics(userId, {
        seed, count: days, audience, tone, excludeTitles,
      });
    } catch {
      throw new BlogPlanError(503, "AI 主题生成失败，请稍后重试");
    }
    if (!topics.length) throw new BlogPlanError(502, "AI 主题生成失败，请稍后重试");
    const activeCount = await this.repository.countActive(userId);
    if (activeCount + topics.length > 50) {
      throw new BlogPlanError(429, "定时发文计划数量已达到上限，请先暂停或删除部分计划");
    }
    const dayAgo = new Date(now - 86_400_000).toISOString();
    const createdToday = await this.repository.countCreatedSince(userId, dayAgo);
    if (createdToday + topics.length > 20) {
      throw new BlogPlanError(429, "过去24小时创建的定时发文计划已达到上限");
    }

    const current = localParts(now, zone);
    const localToday = Date.UTC(current.year, current.month - 1, current.day);
    const scheduledPlans = topics.map((topic, index) => {
      const dateValue = new Date(localToday + (index + 1) * 86_400_000);
      const scheduledDate = `${dateValue.getUTCFullYear().toString().padStart(4, "0")}-${String(dateValue.getUTCMonth() + 1).padStart(2, "0")}-${String(dateValue.getUTCDate()).padStart(2, "0")}`;
      return planInput(userId, {
        topic,
        audience,
        tone,
        scheduleType: "ONCE",
        scheduledDate,
        timezone: zone,
        triggerTime: timeValue,
        aiOption: "DEEP",
        autoPublish: true,
        syncCsdn,
        syncJuejin,
      }, now);
    });
    const ids = await this.repository.createMany(scheduledPlans);
    const created = await Promise.all(ids.map((id) => this.repository.findById(userId, id)));
    if (created.some((plan) => !plan)) throw new Error("Generated blog plans could not be read back");
    return created.map((plan) => view(plan as BlogPlan));
  }

  async create(userId: number, body: PlanInput) {
    const now = this.now();
    const input = planInput(userId, body, now);
    if (input.knowledgeMaterialId !== null) await this.requireOwnedMaterial(userId, input.knowledgeMaterialId);
    if (input.clientRequestId) {
      const existing = await this.repository.findByRequestId(userId, input.clientRequestId);
      if (existing) {
        if (existing.deletedAt) throw new BlogPlanError(409, "该幂等请求标识已被删除计划使用，请生成新的标识");
        return view(existing);
      }
    }
    if (await this.repository.countActive(userId) >= 50) {
      throw new BlogPlanError(429, "定时发文计划数量已达到上限，请先暂停或删除部分计划");
    }
    const dayAgo = new Date(now - 86_400_000).toISOString();
    if (await this.repository.countCreatedSince(userId, dayAgo) >= 20) {
      throw new BlogPlanError(429, "过去24小时创建的定时发文计划已达到上限");
    }
    const id = await this.repository.create(input);
    const created = await this.repository.findById(userId, id);
    if (!created) throw new Error("Blog plan disappeared after creation");
    return view(created);
  }

  async list(userId: number, page: number, size: number) {
    const safePage = countParam(page, 1, 2_147_483_647);
    const safeSize = countParam(size, 10, 50);
    const [records, total] = await Promise.all([
      this.repository.list(userId, safeSize, (safePage - 1) * safeSize),
      this.repository.count(userId),
    ]);
    return { records: records.map(view), total, page: safePage, size: safeSize };
  }

  async detail(userId: number, planId: number) {
    return view(await this.requirePlan(userId, planId));
  }

  async update(userId: number, planId: number, patch: PlanInput) {
    const current = await this.requirePlan(userId, planId);
    if (["RUNNING", "CANCELLED", "FINISHED", "FAILED"].includes(current.status)) {
      throw new BlogPlanError(409, "计划执行中、已取消、已完结或已失败，暂不能修改");
    }
    const next: BlogPlan = { ...current };
    if (patch.topic !== undefined && patch.topic !== null && patch.topic !== "") next.topic = normalizedTopic(patch.topic);
    for (const [key, max, label] of [["audience", 120, "目标读者"], ["tone", 60, "文风语气"]] as const) {
      if (patch[key] !== undefined && patch[key] !== null) next[key] = normalizedText(patch[key], label, max);
    }
    if (patch.syncCsdn !== undefined && patch.syncCsdn !== null) next.syncCsdn = boolean(patch.syncCsdn, current.syncCsdn);
    if (patch.syncJuejin !== undefined && patch.syncJuejin !== null) next.syncJuejin = boolean(patch.syncJuejin, current.syncJuejin);
    if (patch.autoPublish !== undefined && patch.autoPublish !== null) next.autoPublish = boolean(patch.autoPublish, current.autoPublish);
    if (patch.aiOption !== undefined && patch.aiOption !== null) {
      if (typeof patch.aiOption !== "string" || !AI_OPTIONS.has(patch.aiOption)) throw new BlogPlanError(400, "不支持的 AI 规划方向");
      next.aiOption = patch.aiOption as BlogPlan["aiOption"];
    }
    if (patch.knowledgeMaterialId !== undefined && patch.knowledgeMaterialId !== null) {
      if (typeof patch.knowledgeMaterialId !== "number" || !Number.isSafeInteger(patch.knowledgeMaterialId) || patch.knowledgeMaterialId < 1) {
        throw new BlogPlanError(400, "知识材料 ID 无效");
      }
      await this.requireOwnedMaterial(userId, patch.knowledgeMaterialId);
      next.knowledgeMaterialId = patch.knowledgeMaterialId;
    }
    const scheduleChanged = patch.scheduleType !== undefined && patch.scheduleType !== null
      || patch.scheduledDate !== undefined && patch.scheduledDate !== null
      || patch.timezone !== undefined && patch.timezone !== null && patch.timezone !== ""
      || patch.triggerTime !== undefined && patch.triggerTime !== null && patch.triggerTime !== ""
      || patch.triggerTimes !== undefined && patch.triggerTimes !== null;
    if (patch.scheduleType !== undefined && patch.scheduleType !== null) {
      if (typeof patch.scheduleType !== "string" || !SCHEDULES.has(patch.scheduleType as BlogPlanScheduleType)) {
        throw new BlogPlanError(400, "计划类型只支持 DAILY / WEEKLY / ONCE");
      }
      next.scheduleType = patch.scheduleType as BlogPlanScheduleType;
      next.runOnce = next.scheduleType === "ONCE";
    }
    if (patch.scheduledDate !== undefined && patch.scheduledDate !== null) {
      next.scheduledDate = date(patch.scheduledDate, current.scheduledDate);
    }
    next.timezone = timezone(patch.timezone, current.timezone);
    if (patch.triggerTimes !== undefined && patch.triggerTimes !== null) {
      next.triggerTimes = normalizeTimes({ triggerTimes: patch.triggerTimes });
    } else if (patch.triggerTime !== undefined && patch.triggerTime !== null && patch.triggerTime !== "") {
      next.triggerTimes = normalizeTimes({ triggerTime: patch.triggerTime });
    }
    next.triggerTime = next.triggerTimes[0];
    if (patch.topics !== undefined && patch.topics !== null) {
      next.topics = jsonArray(patch.topics, "主题队列", 30, 500);
      next.nextTopicIndex = 0;
    }
    if (scheduleChanged) next.nextRunAt = new Date(nextOccurrence(next, this.now())).toISOString();
    if (!await this.repository.update(next, current.status)) throw new BlogPlanError(409, "计划状态已变化，请刷新后重试");
    return this.detail(userId, planId);
  }

  async updateStatus(userId: number, planId: number, action: unknown) {
    const current = await this.requirePlan(userId, planId);
    if (typeof action !== "string") throw new BlogPlanError(400, "计划操作无效");
    const normalized = action.trim().toUpperCase();
    let from: BlogPlanStatus[];
    let to: BlogPlanStatus;
    let values: { nextRunAt?: string | null; errorMessage?: string | null } = {};
    if (normalized === "PAUSED") { from = ["ACTIVE"]; to = "PAUSED"; }
    else if (normalized === "RESUME") {
      from = ["PAUSED"]; to = "ACTIVE";
      if (!current.runOnce && (!current.nextRunAt || Date.parse(current.nextRunAt) <= this.now())) {
        values.nextRunAt = new Date(nextOccurrence(current, this.now())).toISOString();
      }
      values.errorMessage = null;
    } else if (normalized === "CANCELLED") {
      from = ["ACTIVE", "PAUSED", "FAILED"]; to = "CANCELLED"; values.errorMessage = null;
    } else throw new BlogPlanError(400, "计划操作只支持 PAUSED / RESUME / CANCELLED");
    if (!await this.repository.setStatus(userId, planId, from, to, values)) {
      throw new BlogPlanError(409, "当前状态无法执行该操作");
    }
    return this.detail(userId, planId);
  }

  async delete(userId: number, planId: number) {
    const current = await this.requirePlan(userId, planId);
    if (current.status === "RUNNING") throw new BlogPlanError(409, "计划正在执行，暂不能删除");
    if (!await this.repository.softDelete(userId, planId, new Date(this.now()).toISOString())) {
      throw new BlogPlanError(409, "计划状态已变化，请刷新后重试");
    }
    return null;
  }

  async nextRun(userId: number, planId: number, count: number) {
    const plan = await this.requirePlan(userId, planId);
    const safeCount = countParam(count, 1, 20);
    if (plan.scheduleType === "ONCE" && safeCount > 1) throw new BlogPlanError(400, "一次性计划只能预览一个执行时间");
    let reference = this.now();
    let result = reference;
    for (let index = 0; index < safeCount; index += 1) {
      result = nextOccurrence(plan, reference);
      reference = result;
    }
    return new Date(result).toISOString();
  }

  async listRuns(userId: number, planId: number, page: number, size: number) {
    await this.requirePlan(userId, planId);
    const safePage = countParam(page, 1, 2_147_483_647);
    const safeSize = countParam(size, 10, 50);
    const [records, total] = await Promise.all([
      this.repository.listRuns(planId, safeSize, (safePage - 1) * safeSize),
      this.repository.countRuns(planId),
    ]);
    return { records, total, page: safePage, size: safeSize };
  }

  async runDetail(userId: number, planId: number, runId: number): Promise<BlogPlanRun> {
    await this.requirePlan(userId, planId);
    const run = await this.repository.findRun(planId, runId);
    if (!run) throw new BlogPlanError(404, "计划执行记录不存在");
    return run;
  }

  private async requirePlan(userId: number, planId: number): Promise<BlogPlan> {
    const plan = await this.repository.findById(userId, planId);
    if (!plan) throw new BlogPlanError(404, "定时发文计划不存在");
    return plan;
  }

  private async requireOwnedMaterial(userId: number, materialId: number) {
    if (!this.options.materials) throw new BlogPlanError(503, "知识材料验证服务尚未配置");
    const result = await this.options.materials.checkOwned(userId, materialId);
    if (result === "not_found") throw new BlogPlanError(404, "知识材料不存在或无权访问");
    if (result !== "owned") throw new BlogPlanError(503, "知识材料验证服务暂不可用");
  }
}
