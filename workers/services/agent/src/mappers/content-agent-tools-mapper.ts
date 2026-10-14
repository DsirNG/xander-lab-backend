import { AgentContentContractError, type AgentContentQueryResult, type AgentContentToolsPort } from "../interfaces/agent-content-tools-port.js";
import type { AgentEnvironment } from "../interfaces/agent-environment.js";

const PAGE_SIZE = 50;
const MAX_SCAN_PAGES = 10;

function object(value: unknown): Record<string, unknown> | null {
  return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : null;
}

function text(value: unknown): string { return typeof value === "string" ? value.trim() : ""; }
function safeInt(value: unknown, fallback: number, max: number): number {
  return typeof value === "number" && Number.isSafeInteger(value) && value > 0 ? Math.min(value, max) : fallback;
}
function statusCode(value: unknown): number | null {
  const status = text(value).toLowerCase();
  return status === "draft" ? 0 : status === "published" || status === "publish" ? 1
    : status === "trashed" || status === "trash" || status === "deleted" ? -1 : null;
}
function postStatus(value: unknown): string {
  return value === 0 || value === "0" ? "draft" : value === 1 || value === "1" ? "published"
    : value === -1 || value === "-1" ? "trashed" : "unknown";
}
function dateValue(value: unknown): string { return typeof value === "string" ? value : ""; }
function postRecord(value: unknown) {
  const row = object(value) ?? {};
  const id = typeof row.id === "number" && Number.isSafeInteger(row.id) ? row.id : 0;
  return {
    id, title: text(row.title), summary: text(row.summary), status: postStatus(row.status),
    date: dateValue(row.date), category: text(row.categoryName ?? row.category),
    url: `/blog/${id}/`, juejinSynced: row.juejinSynced === true, csdnSynced: row.csdnSynced === true,
    tags: Array.isArray(row.tags) ? row.tags.filter((tag): tag is string => typeof tag === "string") : [],
  };
}

function listEnvelope(value: unknown): { records: unknown[]; hasMore: boolean } {
  const root = object(value);
  const data = object(root?.data);
  if (root?.code !== 200 || !data || !Array.isArray(data.records)) throw new Error("Invalid Content list response");
  return { records: data.records, hasMore: data.hasMore === true };
}

function briefPosts(posts: ReturnType<typeof postRecord>[], total: number, prefix: string) {
  const lines = posts.map((post) => `- #${post.id} ${post.title || "未命名文章"}（${post.status}，${post.date || "日期未知"}；CSDN ${post.csdnSynced ? "已同步" : "未同步"}，掘金 ${post.juejinSynced ? "已同步" : "未同步"}）`);
  return { ok: true, total, posts, hint: lines.length ? `${prefix}：共 ${total} 篇。\n${lines.join("\n")}` : `${prefix}：没有找到文章。` };
}

function briefPlans(plans: Record<string, unknown>[], total: number) {
  const lines = plans.map((plan) => `- #${plan.id} ${plan.topic || "未命名计划"}（${plan.status || "未知状态"}，下次运行 ${plan.nextRunAt || "未安排"}）`);
  return { ok: true, total, plans, hint: lines.length ? `找到 ${total} 个定时发文计划。\n${lines.join("\n")}` : "当前没有符合条件的定时发文计划。" };
}

function briefReminders(reminders: Record<string, unknown>[], total: number) {
  const lines = reminders.map((item) => `- #${item.id} ${item.subject || "无主题"} → ${item.recipientEmail || "未知收件人"}（${item.status || "未知状态"}，${item.scheduledAt || "未安排"}）`);
  return { ok: true, total, reminders, hint: lines.length ? `找到 ${total} 个邮件提醒。\n${lines.join("\n")}` : "当前没有符合条件的邮件提醒。" };
}

/** Queries Content-owned rows through Gateway-compatible owner headers and authenticated HTTPS. */
export class ContentAgentToolsMapper implements AgentContentToolsPort {
  constructor(private readonly env: AgentEnvironment) {}

  async queryPosts(userId: number, args: Record<string, unknown>): Promise<AgentContentQueryResult> {
    const status = statusCode(args.status);
    const search = typeof args.search === "string" ? args.search.trim().slice(0, 300) : "";
    const juejinSynced = typeof args.juejinSynced === "boolean" ? args.juejinSynced : null;
    const csdnSynced = typeof args.csdnSynced === "boolean" ? args.csdnSynced : null;
    const limit = safeInt(args.limit, 0, 50) || null;
    const page = safeInt(args.page, 1, 100_000);
    const size = safeInt(args.size, 10, 50);
    const statuses = status === null ? [0, 1, -1] : [status];
    const gathered: ReturnType<typeof postRecord>[] = [];
    let truncated = false;
    for (const currentStatus of statuses) {
      for (let currentPage = 1; currentPage <= MAX_SCAN_PAGES; currentPage += 1) {
        const query = new URLSearchParams({ page: String(currentPage), size: String(PAGE_SIZE), status: String(currentStatus) });
        if (search) query.set("search", search);
        const envelope = listEnvelope(await this.call(`/api/blog/posts/mine?${query}`, userId));
        gathered.push(...envelope.records.map(postRecord));
        if (!envelope.hasMore) break;
        if (currentPage === MAX_SCAN_PAGES) truncated = true;
      }
    }
    gathered.sort((left, right) => right.date.localeCompare(left.date) || right.id - left.id);
    const filtered = gathered.filter((post) => (juejinSynced === null || post.juejinSynced === juejinSynced)
      && (csdnSynced === null || post.csdnSynced === csdnSynced));
    let selected: ReturnType<typeof postRecord>[];
    let total: number;
    if (limit !== null) {
      selected = filtered.slice(0, limit); total = selected.length;
    } else {
      total = filtered.length;
      selected = filtered.slice((page - 1) * size, page * size);
    }
    const data = briefPosts(selected, total, "文章查询完成");
    if (truncated) data.hint += "（查询最多扫描 1500 篇，结果可能不完整。）";
    return { data, answer: data.hint };
  }

  async queryPlans(userId: number, args: Record<string, unknown>): Promise<AgentContentQueryResult> {
    const status = text(args.status).toUpperCase();
    const plans: Record<string, unknown>[] = [];
    let truncated = false;
    for (let page = 1; page <= MAX_SCAN_PAGES; page += 1) {
      const result = object(await this.call(`/api/blog-plans?page=${page}&size=${PAGE_SIZE}`, userId));
      const data = object(result?.data);
      if (result?.code !== 200 || !data || !Array.isArray(data.records)) throw new Error("Invalid plan response");
      plans.push(...data.records.map((value) => object(value) ?? {}));
      if (data.hasMore !== true) break;
      if (page === MAX_SCAN_PAGES) truncated = true;
    }
    const selected = plans.filter((plan) => !status || status === "ALL" || text(plan.status).toUpperCase() === status).slice(0, 100);
    const data = briefPlans(selected, selected.length);
    if (truncated) data.hint += "（查询最多扫描 500 篇，结果可能不完整。）";
    return { data, answer: data.hint };
  }

  async queryPlanSeries(userId: number, args: Record<string, unknown>): Promise<AgentContentQueryResult> {
    const seriesId = safeInt(args.seriesId, 0, Number.MAX_SAFE_INTEGER);
    const result = object(await this.call(seriesId ? `/api/blog-plan-series/${seriesId}` : "/api/blog-plan-series", userId));
    if (result?.code !== 200) throw new AgentContentContractError(503, "系列发文计划查询失败");
    const rows = seriesId ? [object(result.data)].filter((row): row is Record<string, unknown> => Boolean(row))
      : Array.isArray(result.data) ? result.data.map((row) => object(row)).filter((row): row is Record<string, unknown> => Boolean(row)) : null;
    if (!rows) throw new AgentContentContractError(503, "系列发文计划响应无效");
    const status = text(args.status).toUpperCase();
    const selected = rows.filter((row) => !status || status === "ALL" || text(row.status).toUpperCase() === status);
    const summaries = selected.map((row) => {
      const topics = Array.isArray(row.topics) ? row.topics.filter((topic): topic is string => typeof topic === "string") : [];
      const items = Array.isArray(row.items) ? row.items.map((item) => object(item)).filter((item): item is Record<string, unknown> => Boolean(item)) : [];
      const head = `系列 #${row.id}《${text(row.title) || text(row.topic)}》：${text(row.status)}，${topics.length} 天，${text(row.startDate)} 开始。`;
      const topicLines = topics.map((topic, index) => `  第 ${index + 1} 天：${topic}`);
      const progress = items.length ? `已建立 ${items.length} 个每日计划。` : "尚未启用，没有建立每日计划。";
      return [head, ...topicLines, progress].join("\n");
    });
    const answer = summaries.length ? `找到 ${summaries.length} 个系列发文计划。\n${summaries.join("\n\n")}` : "当前没有符合条件的系列发文计划。";
    return { data: { ok: true, total: selected.length, series: selected }, answer };
  }

  async queryEmailReminders(userId: number, args: Record<string, unknown>): Promise<AgentContentQueryResult> {
    const query = new URLSearchParams({ page: "1", size: "20" });
    if (typeof args.status === "string" && args.status.trim()) query.set("status", args.status.trim().toUpperCase());
    if (typeof args.search === "string" && args.search.trim()) query.set("search", args.search.trim().slice(0, 120));
    const reminders: Record<string, unknown>[] = [];
    let truncated = false;
    for (let page = 1; page <= MAX_SCAN_PAGES; page += 1) {
      query.set("page", String(page));
      const result = object(await this.call(`/api/email-reminders?${query}`, userId));
      const data = object(result?.data);
      if (result?.code !== 200 || !data || !Array.isArray(data.records)) throw new Error("Invalid reminder response");
      reminders.push(...data.records.map((value) => object(value) ?? {}));
      if (data.hasMore !== true) break;
      if (page === MAX_SCAN_PAGES) truncated = true;
    }
    const selected = reminders.slice(0, 100);
    const data = briefReminders(selected, selected.length);
    if (truncated) data.hint += "（查询最多扫描 200 条，结果可能不完整。）";
    return { data, answer: data.hint };
  }

  async queryUnsyncedPosts(userId: number, args: Record<string, unknown>): Promise<AgentContentQueryResult> {
    const platform = text(args.platform).toLowerCase() === "csdn" ? "csdn" : "juejin";
    const delegated = await this.queryPosts(userId, {
      status: args.status, search: args.search, [platform === "csdn" ? "csdnSynced" : "juejinSynced"]: false,
      limit: 50,
    });
    const data: Record<string, unknown> = { ...delegated.data, platform };
    const posts = Array.isArray(data.posts) ? data.posts as ReturnType<typeof postRecord>[] : [];
    const lines = posts.map((post) => `- #${post.id} ${post.title || "未命名文章"}（${post.status}，${post.date || "日期未知"}）`);
    const answer = lines.length ? `${platform === "csdn" ? "CSDN" : "掘金"}未同步文章共 ${posts.length} 篇。\n${lines.join("\n")}`
      : `当前没有未同步到${platform === "csdn" ? "CSDN" : "掘金"}的文章。`;
    return { data, answer };
  }

  async createPlan(userId: number, args: Record<string, unknown>, requestId: string): Promise<AgentContentQueryResult> {
    const triggerTimes = Array.isArray(args.triggerTimes)
      ? args.triggerTimes.filter((value): value is string => typeof value === "string").slice(0, 5)
      : [];
    const triggerTime = text(args.triggerTime);
    const result = await this.call("/api/blog-plans", userId, "POST", {
      clientRequestId: requestId,
      topic: text(args.topic),
      topics: Array.isArray(args.topics) ? args.topics : [],
      triggerTimes: triggerTimes.length ? triggerTimes : [triggerTime || "09:00"],
      timezone: text(args.timezone) || "Asia/Shanghai",
      audience: text(args.audience),
      tone: text(args.tone),
      syncCsdn: typeof args.syncCsdn === "boolean" ? args.syncCsdn : false,
      syncJuejin: typeof args.syncJuejin === "boolean" ? args.syncJuejin : false,
      autoPublish: true,
    });
    const data = mutationData(result, "Content plan creation failed");
    return { data: { ok: true, plan: data }, answer: `定时发文计划已创建：${text(data.topic) || text(args.topic)}。` };
  }

  async createPlanSeries(userId: number, args: Record<string, unknown>, requestId: string): Promise<AgentContentQueryResult> {
    const payload: Record<string, unknown> = { clientRequestId: requestId };
    for (const key of ["title", "topic", "days", "topics", "startDate", "triggerTime", "timezone",
      "audience", "tone", "platforms", "publishMode", "aiOption"] as const) {
      if (Object.hasOwn(args, key)) payload[key] = args[key];
    }
    const result = mutationData(await this.call("/api/blog-plan-series", userId, "POST", payload), "系列草案创建失败");
    const topics = Array.isArray(result.topics) ? result.topics.filter((topic): topic is string => typeof topic === "string") : [];
    const lines = topics.map((topic, index) => `${index + 1}. ${topic}`);
    const answer = `已保存系列草案 #${result.id}《${text(result.title) || text(result.topic)}》，共 ${topics.length} 天。\n${lines.join("\n")}\n\n请检查每日主题；明确确认后，我再启用计划。`;
    return { data: { ok: true, series: result }, answer };
  }

  async updatePlanSeries(userId: number, args: Record<string, unknown>): Promise<AgentContentQueryResult> {
    const id = safeInt(args.seriesId, 0, Number.MAX_SAFE_INTEGER);
    if (!id) throw new AgentContentContractError(400, "系列计划 ID 无效");
    const payload: Record<string, unknown> = {};
    for (const key of ["title", "topic", "days", "topics", "startDate", "triggerTime", "timezone",
      "audience", "tone", "platforms", "publishMode", "aiOption"] as const) {
      if (Object.hasOwn(args, key)) payload[key] = args[key];
    }
    const result = mutationData(await this.call(`/api/blog-plan-series/${id}`, userId, "PATCH", payload), "系列草案更新失败");
    const topics = Array.isArray(result.topics) ? result.topics.filter((topic): topic is string => typeof topic === "string") : [];
    return { data: { ok: true, series: result }, answer: `系列草案 #${id} 已更新。\n${topics.map((topic, index) => `${index + 1}. ${topic}`).join("\n")}` };
  }

  async activatePlanSeries(userId: number, args: Record<string, unknown>): Promise<AgentContentQueryResult> {
    const id = safeInt(args.seriesId, 0, Number.MAX_SAFE_INTEGER);
    if (!id) throw new AgentContentContractError(400, "系列计划 ID 无效");
    const result = mutationData(await this.call(`/api/blog-plan-series/${id}/activate`, userId, "POST"), "系列计划启用失败");
    const items = Array.isArray(result.items) ? result.items : [];
    return { data: { ok: true, series: result }, answer: `系列计划《${text(result.title) || text(result.topic)}》已启用，已安排 ${items.length} 天的文章计划。` };
  }

  async retryPlanSeries(userId: number, args: Record<string, unknown>): Promise<AgentContentQueryResult> {
    const id = safeInt(args.seriesId, 0, Number.MAX_SAFE_INTEGER);
    if (!id) throw new AgentContentContractError(400, "系列计划 ID 无效");
    const result = mutationData(await this.call(`/api/blog-plan-series/${id}/status`, userId, "PATCH", {
      action: "RETRY_FAILED",
    }), "系列失败计划重试失败");
    return { data: { ok: true, series: result }, answer: `系列《${text(result.title) || text(result.topic)}》的失败每日计划已重排，当前状态：${text(result.status)}。` };
  }

  async updatePlan(userId: number, args: Record<string, unknown>): Promise<AgentContentQueryResult> {
    const id = safeInt(args.planId, 0, Number.MAX_SAFE_INTEGER);
    const action = text(args.action).toUpperCase();
    if (!id || !["PAUSED", "PAUSE", "RESUME", "CANCELLED", "CANCEL", "DELETE", "EDIT"].includes(action)) {
      throw new AgentContentContractError(400, "计划操作参数无效");
    }
    let response: unknown;
    let normalizedAction = action;
    if (action === "DELETE") {
      response = await this.call(`/api/blog-plans/${id}`, userId, "DELETE");
    } else if (["PAUSED", "PAUSE", "RESUME", "CANCELLED", "CANCEL"].includes(action)) {
      normalizedAction = action === "PAUSE" ? "PAUSED" : action === "CANCEL" ? "CANCELLED" : action;
      response = await this.call(`/api/blog-plans/${id}/status`, userId, "PATCH", { action: normalizedAction });
    } else {
      const patch: Record<string, unknown> = {};
      for (const key of ["topic", "topics", "triggerTime", "triggerTimes", "timezone", "audience", "tone"] as const) {
        if (Object.hasOwn(args, key)) patch[key] = args[key];
      }
      for (const key of ["syncCsdn", "syncJuejin"] as const) {
        if (typeof args[key] === "boolean") patch[key] = args[key];
      }
      if (!Object.keys(patch).length) throw new AgentContentContractError(400, "编辑计划时至少提供一个新值");
      response = await this.call(`/api/blog-plans/${id}`, userId, "PATCH", patch);
    }
    const data = action === "DELETE" ? mutationData(response, "Content plan deletion failed", true)
      : mutationData(response, "Content plan update failed");
    const hint = action === "DELETE" ? "定时发文计划已删除。"
      : action === "EDIT" ? "定时发文计划已更新。"
        : `定时发文计划已${normalizedAction === "PAUSED" ? "暂停" : normalizedAction === "RESUME" ? "恢复" : "取消"}。`;
    return { data: { ok: true, planId: id, action: normalizedAction, ...(data ? { plan: data } : {}) }, answer: hint };
  }

  async createEmailReminder(userId: number, args: Record<string, unknown>, requestId: string): Promise<AgentContentQueryResult> {
    const payload: Record<string, unknown> = { clientRequestId: requestId };
    for (const key of [
      "recipientEmail", "subject", "message", "frequency", "scheduledAt", "sendTime", "timezone",
      "recurrenceDay", "intervalDays", "contentType", "templateId",
    ] as const) {
      if (Object.hasOwn(args, key)) payload[key] = args[key];
    }
    const result = await this.call("/api/email-reminders", userId, "POST", payload);
    const data = mutationData(result, "Content reminder creation failed");
    return { data: { ok: true, reminder: data }, answer: `定时邮件提醒已创建，将发送到 ${text(data.recipientEmail) || text(args.recipientEmail)}。` };
  }

  async updateEmailReminder(userId: number, args: Record<string, unknown>): Promise<AgentContentQueryResult> {
    const id = safeInt(args.taskId, 0, Number.MAX_SAFE_INTEGER);
    const action = text(args.action).toUpperCase();
    if (!id || !["PAUSE", "PAUSED", "RESUME", "DELETE"].includes(action)) {
      throw new AgentContentContractError(400, "邮件提醒操作参数无效");
    }
    const response = action === "DELETE"
      ? await this.call(`/api/email-reminders/${id}`, userId, "DELETE")
      : await this.call(`/api/email-reminders/${id}/status`, userId, "PATCH", {
        status: action === "RESUME" ? "PENDING" : "PAUSED",
      });
    const data = action === "DELETE"
      ? mutationData(response, "Content reminder deletion failed", true)
      : mutationData(response, "Content reminder update failed");
    const normalizedAction = action === "PAUSE" ? "PAUSED" : action;
    const hint = action === "DELETE" ? "定时邮件提醒已删除。"
      : `定时邮件提醒已${normalizedAction === "RESUME" ? "恢复" : "暂停"}。`;
    return { data: { ok: true, taskId: id, action: normalizedAction, ...(data ? { reminder: data } : {}) }, answer: hint };
  }

  private async call(path: string, userId: number, method = "GET", body?: unknown): Promise<unknown> {
    const base = this.env.CONTENT_AUTOMATION_SERVICE_URL?.trim();
    const token = this.env.CONTENT_AUTOMATION_SERVICE_TOKEN;
    if (!base || !token) throw new Error("Content query contract is not configured");
    let url: URL;
    try { url = new URL(path, base); } catch { throw new Error("Content query contract is not configured"); }
    if (url.protocol !== "https:" && url.hostname !== "localhost" && url.hostname !== "127.0.0.1") {
      throw new Error("Content query contract must use HTTPS");
    }
    const headers = new Headers({ "x-dinqor-service-token": token, "x-user-id": String(userId) });
    if (body !== undefined) headers.set("content-type", "application/json");
    const request = new Request(url, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      redirect: "manual", signal: AbortSignal.timeout(5_000),
    });
    const response = this.env.CONTENT_AUTOMATION_SERVICE
      ? await this.env.CONTENT_AUTOMATION_SERVICE.fetch(request) : await fetch(request);
    let value: unknown;
    try { value = await response.json() as unknown; }
    catch { throw new AgentContentContractError(503, "内容服务返回了无效响应"); }
    const root = object(value);
    const status = typeof root?.code === "number" ? root.code : response.status;
    if (!response.ok || (status !== 200 && root?.code !== undefined)) {
      throw new AgentContentContractError(status >= 400 && status <= 599 ? status : 503, "内容服务请求失败");
    }
    return value;
  }
}

function mutationData(value: unknown, message: string): Record<string, unknown>;
function mutationData(value: unknown, message: string, allowNull: true): Record<string, unknown> | null;
function mutationData(value: unknown, message: string, allowNull = false): Record<string, unknown> | null {
  const root = object(value);
  if (root?.code !== 200) {
    const status = typeof root?.code === "number" ? root.code : 503;
    throw new AgentContentContractError(status >= 400 && status <= 599 ? status : 503, message);
  }
  const data = object(root.data);
  if (data) return data;
  if (allowNull && root.data === null) return null;
  throw new AgentContentContractError(503, message);
}
