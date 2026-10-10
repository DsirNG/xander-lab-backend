import type {
  EmailReminderBillingPort, EmailReminderCreateInput, EmailReminderMailPort,
  EmailReminderRepository, EmailReminderTask,
} from "../interfaces/email-reminder-repository.js";

const EMAIL_PATTERN = /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/;
const FREQUENCIES = new Set(["ONCE", "DAILY", "WEEKLY", "MONTHLY", "CUSTOM"]);
const TEMPLATES = new Set(["classic", "minimal", "card", "notice", "none"]);

export class EmailReminderError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
    this.name = "EmailReminderError";
  }
}

type LocalTime = { year: number; month: number; day: number; hour: number; minute: number };

function localParts(date: Date, timezone: string): LocalTime {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: timezone, year: "numeric", month: "2-digit", day: "2-digit",
    hour: "2-digit", minute: "2-digit", hourCycle: "h23",
  }).formatToParts(date);
  const n = (name: string) => Number(parts.find((p) => p.type === name)?.value);
  return { year: n("year"), month: n("month"), day: n("day"), hour: n("hour"), minute: n("minute") };
}

function localEpoch(value: LocalTime): number {
  return Date.UTC(value.year, value.month - 1, value.day, value.hour, value.minute);
}

function dateKey(value: LocalTime): string {
  return String(value.year).padStart(4, "0") + "-" + String(value.month).padStart(2, "0") + "-" + String(value.day).padStart(2, "0");
}

function addDays(date: string, days: number): string {
  const result = new Date(date + "T00:00:00.000Z");
  result.setUTCDate(result.getUTCDate() + days);
  return result.toISOString().slice(0, 10);
}

function atLocal(date: string, time: string, timezone: string): Date {
  const [year, month, day] = date.split("-").map(Number);
  const [hour, minute] = time.split(":").map(Number);
  const wanted = localEpoch({ year, month, day, hour, minute });
  let candidate = wanted;
  for (let i = 0; i < 5; i++) {
    const difference = wanted - localEpoch(localParts(new Date(candidate), timezone));
    if (difference === 0) return new Date(candidate);
    candidate += difference;
  }
  // Resolve nonexistent local times by moving forward through the DST gap.
  const options: Array<{ local: number; instant: number }> = [];
  for (let delta = -180; delta <= 180; delta++) {
    const instant = candidate + delta * 60_000;
    const actual = localParts(new Date(instant), timezone);
    const local = localEpoch(actual);
    if (dateKey(actual) === date && local >= wanted) options.push({ local, instant });
  }
  options.sort((a, b) => a.local - b.local || a.instant - b.instant);
  return new Date(options[0]?.instant ?? candidate);
}

function nextDaily(timezone: string, time: string, after: Date): Date {
  const local = localParts(after, timezone);
  const key = dateKey(local);
  const candidate = atLocal(key, time, timezone);
  return candidate.getTime() > after.getTime() ? candidate : atLocal(addDays(key, 1), time, timezone);
}

function nextWeekly(timezone: string, time: string, targetDay: number, after: Date): Date {
  const local = localParts(after, timezone);
  const weekday = new Date(dateKey(local) + "T00:00:00.000Z").getUTCDay() || 7;
  let days = targetDay - weekday;
  if (days < 0) days += 7;
  const candidate = atLocal(addDays(dateKey(local), days), time, timezone);
  return candidate.getTime() > after.getTime()
    ? candidate : atLocal(addDays(dateKey(local), days + 7), time, timezone);
}

function nextMonthly(timezone: string, time: string, targetDay: number, after: Date): Date {
  const local = localParts(after, timezone);
  const candidateFor = (offset: number) => {
    const month = new Date(Date.UTC(local.year, local.month - 1 + offset, 1));
    const lastDay = new Date(Date.UTC(month.getUTCFullYear(), month.getUTCMonth() + 1, 0)).getUTCDate();
    const day = Math.min(targetDay, lastDay);
    const date = String(month.getUTCFullYear()).padStart(4, "0") + "-"
      + String(month.getUTCMonth() + 1).padStart(2, "0") + "-" + String(day).padStart(2, "0");
    return atLocal(date, time, timezone);
  };
  const candidate = candidateFor(0);
  return candidate.getTime() > after.getTime() ? candidate : candidateFor(1);
}

function nextOccurrence(task: EmailReminderTask, now: Date): Date | null {
  if (task.frequency === "ONCE") return null;
  if (!task.sendTime) throw new Error("任务发送时间无效");
  const previous = new Date(task.scheduledAt);
  const after = new Date(previous.getTime() + 1_000);
  if (task.frequency === "DAILY") return nextDaily(task.timezone, task.sendTime, after);
  if (task.frequency === "WEEKLY") return nextWeekly(task.timezone, task.sendTime, task.recurrenceDay!, after);
  if (task.frequency === "MONTHLY") return nextMonthly(task.timezone, task.sendTime, task.recurrenceDay!, after);
  let candidate = atLocal(addDays(dateKey(localParts(previous, task.timezone)), task.intervalDays!),
    task.sendTime, task.timezone);
  while (candidate.getTime() <= now.getTime()) {
    candidate = atLocal(addDays(dateKey(localParts(candidate, task.timezone)), task.intervalDays!),
      task.sendTime, task.timezone);
  }
  return candidate;
}

function escapeHtml(value: string): string {
  return value.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;").replaceAll("'", "&#39;");
}

function sanitizeHtml(value: string): string {
  return value.replace(/<script\b[^>]*>[\s\S]*?<\/script\s*>/gi, "")
    .replace(/<\/?(?:iframe|object|embed|link|meta|base|form|input|button|textarea|select)\b[^>]*>/gi, "")
    .replace(/\s+on[a-z]+\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]+)/gi, "")
    .replace(/\b(href|src)\s*=\s*(["']?)\s*javascript:/gi, "$1=$2#blocked:");
}

function rendered(task: EmailReminderTask, zone: string) {
  const useHtml = task.contentType === "HTML";
  if (!useHtml) return { text: task.message, html: null as string | null };
  const body = sanitizeHtml(task.message);
  const text = task.message.replace(/<[^>]*>/g, " ").replace(/\s+/g, " ").trim();
  if (task.templateId === "none") return { text, html: body };
  const localTime = new Intl.DateTimeFormat("en-CA", {
    timeZone: zone, year: "numeric", month: "2-digit", day: "2-digit",
    hour: "2-digit", minute: "2-digit", hourCycle: "h23",
  }).format(new Date(task.scheduledAt));
  const html = "<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><body "
    + "style=\"font-family:Arial,sans-serif;color:#1f2937;background:#f3f4f6;padding:24px\">"
    + "<main style=\"max-width:600px;margin:0 auto;background:white;padding:28px;border-radius:12px\">"
    + "<h1>" + escapeHtml(task.subject) + "</h1><div style=\"line-height:1.8\">" + body + "</div>"
    + "<p style=\"color:#6b7280;font-size:12px\">计划发送时间：" + escapeHtml(localTime)
    + " (" + escapeHtml(zone) + ")</p></main></body></html>";
  return { text: text + "\n\n计划发送时间：" + localTime + " (" + zone + ")", html };
}

function publicTask(task: EmailReminderTask, senderEmail?: string) {
  const { processingToken: _processingToken, ...visible } = task;
  return { ...visible, senderEmail: senderEmail ?? "" };
}

export class EmailReminderService {
  constructor(
    private readonly repository: EmailReminderRepository,
    private readonly mail: EmailReminderMailPort,
    private readonly billing: EmailReminderBillingPort,
    private readonly options: { senderEmail?: string; minimumLeadSeconds?: number; displayZone?: string } = {},
    private readonly now: () => Date = () => new Date(),
  ) {}

  async create(userId: number | null, value: unknown) {
    if (!userId || userId < 1) throw new EmailReminderError(401, "未登录或登录已过期");
    if (!value || typeof value !== "object" || Array.isArray(value)) throw new EmailReminderError(400, "邮件提醒格式不正确");
    const body = value as Record<string, unknown>;
    const recipientEmail = typeof body.recipientEmail === "string" ? body.recipientEmail.trim().toLowerCase() : "";
    const subject = typeof body.subject === "string" ? body.subject.trim() : "";
    const message = typeof body.message === "string" ? body.message.trim() : "";
    if (!EMAIL_PATTERN.test(recipientEmail) || recipientEmail.length > 254) throw new EmailReminderError(400, "收件邮箱格式不正确");
    if (!subject || subject.length > 200 || /[\r\n]/.test(subject)) throw new EmailReminderError(400, "邮件主题格式不正确");
    if (!message || message.length > 10_000) throw new EmailReminderError(400, "邮件内容格式不正确");
    const idempotency = body.clientRequestId;
    if (idempotency !== undefined && idempotency !== null
      && (typeof idempotency !== "string" || idempotency.length > 64 || !/^[A-Za-z0-9._:-]+$/.test(idempotency))) {
      throw new EmailReminderError(400, "幂等请求标识格式不正确");
    }
    const clientRequestId = typeof idempotency === "string" && idempotency.trim() ? idempotency.trim() : crypto.randomUUID();
    const rawFrequency = typeof body.frequency === "string" && body.frequency.trim()
      ? body.frequency.trim().toUpperCase() : "ONCE";
    if (!FREQUENCIES.has(rawFrequency)) throw new EmailReminderError(400, "发送频率只支持 ONCE / DAILY / WEEKLY / MONTHLY / CUSTOM");
    const timezone = typeof body.timezone === "string" && body.timezone.trim() ? body.timezone.trim() : "Asia/Shanghai";
    try { new Intl.DateTimeFormat("en", { timeZone: timezone }).format(this.now()); }
    catch { throw new EmailReminderError(400, "时区无效：" + timezone); }
    const sendTime = typeof body.sendTime === "string" ? body.sendTime.trim() : "";
    if (rawFrequency !== "ONCE" && !/^([01]\d|2[0-3]):[0-5]\d$/.test(sendTime)) {
      throw new EmailReminderError(400, "发送时间格式应为 HH:mm");
    }
    const recurrenceDay = Number.isInteger(body.recurrenceDay) ? body.recurrenceDay as number : null;
    const intervalDays = Number.isInteger(body.intervalDays) ? body.intervalDays as number : null;
    if (rawFrequency === "WEEKLY" && (recurrenceDay === null || recurrenceDay < 1 || recurrenceDay > 7)) {
      throw new EmailReminderError(400, "请选择每周几发送（1-7，周一到周日）");
    }
    if (rawFrequency === "MONTHLY" && (recurrenceDay === null || recurrenceDay < 1 || recurrenceDay > 31)) {
      throw new EmailReminderError(400, "请选择每月几号发送（1-31）");
    }
    if (rawFrequency === "CUSTOM" && (intervalDays === null || intervalDays < 1 || intervalDays > 365)) {
      throw new EmailReminderError(400, "自定义间隔天数需在 1-365 之间");
    }
    const now = this.now();
    let scheduledAt: Date;
    if (rawFrequency === "ONCE") {
      const raw = body.scheduledAt;
      const parsed = typeof raw === "string" ? new Date(raw) : null;
      if (!parsed || !Number.isFinite(parsed.getTime()) || !/([zZ]|[+-]\d\d:\d\d)$/.test(raw as string)) {
        throw new EmailReminderError(400, "请选择有效的发送时间");
      }
      scheduledAt = parsed;
    } else if (rawFrequency === "DAILY" || rawFrequency === "CUSTOM") {
      scheduledAt = nextDaily(timezone, sendTime, now);
    } else if (rawFrequency === "WEEKLY") {
      scheduledAt = nextWeekly(timezone, sendTime, recurrenceDay!, now);
    } else {
      scheduledAt = nextMonthly(timezone, sendTime, recurrenceDay!, now);
    }
    const lead = Math.max(1, this.options.minimumLeadSeconds ?? 30);
    if (scheduledAt.getTime() <= now.getTime() + lead * 1000) {
      throw new EmailReminderError(400, "发送时间至少需要晚于当前时间 " + lead + " 秒");
    }
    const requestedTemplate = typeof body.templateId === "string" ? body.templateId.trim().toLowerCase() : "none";
    const explicitContentType = typeof body.contentType === "string" ? body.contentType.trim().toUpperCase() : "";
    const contentType = explicitContentType
      ? explicitContentType === "HTML" ? "HTML" : "PLAIN"
      : TEMPLATES.has(requestedTemplate) && requestedTemplate !== "none"
        || /<\/?[a-z][^>]*>/i.test(message) ? "HTML" : "PLAIN";
    const task = await this.repository.create({
      userId, clientRequestId, recipientEmail, subject, message, contentType,
      templateId: TEMPLATES.has(requestedTemplate) ? requestedTemplate : "none",
      frequency: rawFrequency as EmailReminderCreateInput["frequency"], timezone,
      sendTime: rawFrequency === "ONCE" ? null : sendTime,
      recurrenceDay: rawFrequency === "WEEKLY" || rawFrequency === "MONTHLY" ? recurrenceDay : null,
      intervalDays: rawFrequency === "CUSTOM" ? intervalDays : null,
      scheduledAt: scheduledAt.toISOString(), now: now.toISOString(),
    });
    if ("kind" in task) {
      if (task.kind === "deleted-replay") throw new EmailReminderError(409, "该幂等请求标识已被删除任务使用，请生成新的标识");
      throw new EmailReminderError(429, "定时邮件任务已达到创建上限");
    }
    return publicTask(task, this.options.senderEmail);
  }

  async list(userId: number | null, query: URLSearchParams) {
    if (!userId || userId < 1) throw new EmailReminderError(401, "未登录或登录已过期");
    const page = Math.max(1, Number(query.get("page") ?? 1) || 1);
    const rawSize = Number(query.get("size") ?? 10);
    const size = [5, 10, 15, 20].includes(rawSize) ? rawSize : 10;
    const raw = query.get("status")?.trim().toUpperCase() ?? "";
    let status: string | null = raw || null;
    if (raw === "ALL") status = null;
    else if (raw === "SCHEDULED" || raw === "ACTIVE") status = "PENDING";
    else if (raw === "PROCESSING") status = "SENDING";
    else if (status && !["PENDING", "PAUSED", "SENDING", "SENT", "FAILED"].includes(status)) {
      throw new EmailReminderError(400, "不支持的任务状态筛选");
    }
    const result = await this.repository.listMine({
      userId, status, search: query.get("search")?.trim() ?? "", page, size,
    });
    return { ...result, records: result.records.map((task) => publicTask(task, this.options.senderEmail)) };
  }

  async updateStatus(userId: number | null, taskId: number, requestedStatus: unknown) {
    const task = await this.requireOwned(userId, taskId);
    if (typeof requestedStatus !== "string") throw new EmailReminderError(400, "请选择任务状态");
    const status = requestedStatus.toUpperCase();
    if (status !== "PENDING" && status !== "PAUSED") throw new EmailReminderError(400, "任务状态只支持 PENDING 或 PAUSED");
    if (status === task.status) return publicTask(task, this.options.senderEmail);
    let scheduledAt: string | null = null;
    if (status === "PAUSED") {
      if (task.status !== "PENDING") throw new EmailReminderError(409, "当前状态无法暂停");
    } else {
      if (task.status !== "PAUSED" && task.status !== "FAILED") throw new EmailReminderError(409, "当前状态无法恢复");
      const now = this.now();
      if (new Date(task.scheduledAt).getTime() <= now.getTime()) {
        if (task.frequency === "ONCE") throw new EmailReminderError(400, "计划发送时间已过，请删除后重新创建任务");
        const next = nextOccurrence(task, now);
        if (!next) throw new EmailReminderError(400, "无法恢复该周期任务");
        scheduledAt = next.toISOString();
      }
    }
    const now = this.now().toISOString();
    if (!await this.repository.updateStatus(userId!, taskId, status, scheduledAt, now)) {
      throw new EmailReminderError(409, "任务状态已变化，请刷新后重试");
    }
    return publicTask((await this.requireOwned(userId, taskId)), this.options.senderEmail);
  }

  async delete(userId: number | null, taskId: number): Promise<void> {
    await this.requireOwned(userId, taskId);
    const result = await this.repository.softDelete(userId!, taskId, this.now().toISOString());
    if (result === "sending") throw new EmailReminderError(409, "邮件正在发送，暂时不能删除");
    if (result === "missing") throw new EmailReminderError(409, "任务状态已变化，请刷新后重试");
  }

  async processDue(now: Date): Promise<void> {
    const tasks = await this.repository.claimDue(now.toISOString(),
      new Date(now.getTime() - 600_000).toISOString(),
      new Date(now.getTime() - 30_000).toISOString(), 20);
    await Promise.allSettled(tasks.map((task) => this.sendOne(task, now)));
  }

  private async sendOne(task: EmailReminderTask, now: Date): Promise<void> {
    try {
      const content = rendered(task, this.options.displayZone ?? "Asia/Shanghai");
      if (!await this.mail.send(task.recipientEmail, task.subject, content.text, content.html)) {
        throw new Error("邮件投递暂时失败");
      }
      const next = nextOccurrence(task, now);
      if (!await this.repository.finish(task, next?.toISOString() ?? null, now.toISOString())) return;
      try {
        const points = await this.billing.flatPoints("EMAIL_REMINDER");
        if (points > 0) await this.billing.charge({
          userId: task.userId, bizType: "EMAIL_REMINDER",
          bizKey: "email:" + task.id + ":" + new Date(task.scheduledAt).getTime(),
          bizRef: "task:" + task.id, points, remark: "定时邮件提醒发送成功",
        });
      } catch {
        // Delivery is committed; the ledger request uses a deterministic key for reconciliation.
      }
    } catch (error) {
      const message = error instanceof Error ? error.message.slice(0, 1000) : "邮件投递暂时失败";
      await this.repository.fail(task, message, task.sendAttempts < 3, now.toISOString());
    }
  }

  private async requireOwned(userId: number | null, taskId: number): Promise<EmailReminderTask> {
    if (!userId || userId < 1) throw new EmailReminderError(401, "未登录或登录已过期");
    const task = await this.repository.findMine(userId, taskId);
    if (!task) throw new EmailReminderError(404, "定时邮件任务不存在");
    return task;
  }
}
