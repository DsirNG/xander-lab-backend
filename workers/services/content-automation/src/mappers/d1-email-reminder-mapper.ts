import type { D1Binding } from "@dinqor/worker-runtime";
import type {
  EmailReminderCreateInput, EmailReminderPage, EmailReminderRepository, EmailReminderTask,
} from "../interfaces/email-reminder-repository.js";

type ReminderRow = Omit<EmailReminderTask, "id" | "userId" | "sendAttempts" | "recurrenceDay" | "intervalDays"> & {
  id: number;
  userId: number;
  sendAttempts: number;
  recurrenceDay: number | null;
  intervalDays: number | null;
};

const SELECT = `SELECT id, user_id AS userId, client_request_id AS clientRequestId,
  recipient_email AS recipientEmail, subject, message, content_type AS contentType,
  template_id AS templateId, frequency, timezone, send_time AS sendTime,
  recurrence_day AS recurrenceDay, interval_days AS intervalDays, scheduled_at AS scheduledAt,
  status, send_attempts AS sendAttempts, error_message AS errorMessage,
  processing_token AS processingToken, sent_at AS sentAt, created_at AS createdAt, updated_at AS updatedAt
  FROM email_reminder_task`;

function map(row: ReminderRow): EmailReminderTask {
  return { ...row, id: Number(row.id), userId: Number(row.userId), sendAttempts: Number(row.sendAttempts),
    recurrenceDay: row.recurrenceDay === null ? null : Number(row.recurrenceDay),
    intervalDays: row.intervalDays === null ? null : Number(row.intervalDays) };
}

export class D1EmailReminderMapper implements EmailReminderRepository {
  constructor(private readonly db: D1Binding) {}

  async create(input: EmailReminderCreateInput): Promise<EmailReminderTask | { kind: "quota" | "deleted-replay" }> {
    const existing = await this.db.prepare(`${SELECT} WHERE user_id = ? AND client_request_id = ?`)
      .bind(input.userId, input.clientRequestId).first<ReminderRow & { deletedAt?: string | null }>();
    if (existing) {
      const state = await this.db.prepare("SELECT deleted_at AS deletedAt FROM email_reminder_task WHERE id = ?")
        .bind(existing.id).first<{ deletedAt: string | null }>();
      return state?.deletedAt ? { kind: "deleted-replay" } : map(existing);
    }

    const inserted = await this.db.prepare(`
      INSERT INTO email_reminder_task (
        user_id, client_request_id, recipient_email, subject, message, content_type, template_id,
        frequency, timezone, send_time, recurrence_day, interval_days, scheduled_at,
        status, send_attempts, created_at, updated_at
      )
      SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?
      WHERE (SELECT COUNT(*) FROM email_reminder_task
             WHERE user_id = ? AND deleted_at IS NULL AND status IN ('PENDING', 'PAUSED', 'SENDING')) < 50
        AND (SELECT COUNT(*) FROM email_reminder_task
             WHERE user_id = ? AND created_at >= ?) < 20
      ON CONFLICT(user_id, client_request_id) DO NOTHING
      RETURNING id
    `).bind(
      input.userId, input.clientRequestId, input.recipientEmail, input.subject, input.message,
      input.contentType, input.templateId, input.frequency, input.timezone, input.sendTime,
      input.recurrenceDay, input.intervalDays, input.scheduledAt, input.now, input.now,
      input.userId, input.userId, new Date(Date.parse(input.now) - 86_400_000).toISOString(),
    ).first<{ id: number }>();

    const created = inserted?.id
      ? await this.findMine(input.userId, Number(inserted.id))
      : null;
    if (created) return created;
    const replay = await this.db.prepare(`${SELECT} WHERE user_id = ? AND client_request_id = ?`)
      .bind(input.userId, input.clientRequestId).first<ReminderRow>();
    if (replay) {
      const state = await this.db.prepare("SELECT deleted_at AS deletedAt FROM email_reminder_task WHERE id = ?")
        .bind(replay.id).first<{ deletedAt: string | null }>();
      return state?.deletedAt ? { kind: "deleted-replay" } : map(replay);
    }
    return { kind: "quota" };
  }

  async findMine(userId: number, taskId: number): Promise<EmailReminderTask | null> {
    const row = await this.db.prepare(`${SELECT} WHERE id = ? AND user_id = ? AND deleted_at IS NULL`)
      .bind(taskId, userId).first<ReminderRow>();
    return row ? map(row) : null;
  }

  async listMine(input: { userId: number; status: string | null; search: string; page: number; size: number }): Promise<EmailReminderPage> {
    const filters = ["user_id = ?", "deleted_at IS NULL"];
    const values: Array<string | number> = [input.userId];
    if (input.status) { filters.push("status = ?"); values.push(input.status); }
    if (input.search) {
      filters.push("(subject LIKE '%' || ? || '%' OR recipient_email LIKE '%' || ? || '%')");
      values.push(input.search, input.search);
    }
    const where = filters.join(" AND ");
    const count = await this.db.prepare(`SELECT COUNT(*) AS count FROM email_reminder_task WHERE ${where}`)
      .bind(...values).first<{ count: number }>();
    const total = Number(count?.count ?? 0);
    const rows = await this.db.prepare(`${SELECT} WHERE ${where} ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?`)
      .bind(...values, input.size, (input.page - 1) * input.size).all<ReminderRow>();
    const stats = await this.db.prepare(`SELECT COUNT(*) AS total,
        SUM(CASE WHEN status IN ('PENDING', 'SENDING') THEN 1 ELSE 0 END) AS active,
        SUM(CASE WHEN status = 'SENT' THEN 1 ELSE 0 END) AS sent,
        SUM(CASE WHEN status = 'PENDING' THEN 1 ELSE 0 END) AS pending
      FROM email_reminder_task WHERE user_id = ? AND deleted_at IS NULL`)
      .bind(input.userId).first<{ total: number | null; active: number | null; sent: number | null; pending: number | null }>();
    const pages = total === 0 ? 0 : Math.ceil(total / input.size);
    return { records: rows.results.map(map), total, current: input.page, pages, size: input.size,
      hasMore: input.page < pages, stats: { total: Number(stats?.total ?? 0), active: Number(stats?.active ?? 0),
        sent: Number(stats?.sent ?? 0), pending: Number(stats?.pending ?? 0) } };
  }

  async updateStatus(userId: number, taskId: number, status: "PENDING" | "PAUSED", scheduledAt: string | null, now: string): Promise<boolean> {
    const result = await this.db.prepare(`UPDATE email_reminder_task SET status = ?,
      scheduled_at = COALESCE(?, scheduled_at), error_message = CASE WHEN ? = 'PENDING' THEN NULL ELSE error_message END,
      processing_token = NULL, claimed_at = NULL, updated_at = ?
      WHERE id = ? AND user_id = ? AND deleted_at IS NULL`)
      .bind(status, scheduledAt, status, now, taskId, userId).run();
    return result.meta.changes === 1;
  }

  async softDelete(userId: number, taskId: number, now: string): Promise<"deleted" | "sending" | "missing"> {
    const result = await this.db.prepare(`UPDATE email_reminder_task SET deleted_at = ?, updated_at = ?
      WHERE id = ? AND user_id = ? AND deleted_at IS NULL AND status <> 'SENDING'`)
      .bind(now, now, taskId, userId).run();
    if (result.meta.changes === 1) return "deleted";
    const row = await this.db.prepare(`SELECT status FROM email_reminder_task
      WHERE id = ? AND user_id = ? AND deleted_at IS NULL`).bind(taskId, userId).first<{ status: string }>();
    return row?.status === "SENDING" ? "sending" : "missing";
  }

  async claimDue(now: string, staleBefore: string, retryBefore: string, limit: number): Promise<EmailReminderTask[]> {
    await this.db.prepare(`UPDATE email_reminder_task SET status = 'FAILED', processing_token = NULL,
      claimed_at = NULL, error_message = ?, updated_at = ?
      WHERE status = 'SENDING' AND deleted_at IS NULL AND claimed_at < ?`)
      .bind("发送进程意外中断，任务已停止以避免重复发送，请手动恢复后重试", now, staleBefore).run();
    const due = await this.db.prepare(`SELECT id FROM email_reminder_task
      WHERE status = 'PENDING' AND deleted_at IS NULL AND scheduled_at <= ?
        AND (send_attempts = 0 OR updated_at <= ?)
      ORDER BY scheduled_at, id LIMIT ?`).bind(now, retryBefore, limit).all<{ id: number }>();
    const claimed: EmailReminderTask[] = [];
    for (const item of due.results) {
      const token = crypto.randomUUID();
      const update = await this.db.prepare(`UPDATE email_reminder_task SET status = 'SENDING',
        processing_token = ?, claimed_at = ?, send_attempts = send_attempts + 1, error_message = NULL, updated_at = ?
        WHERE id = ? AND status = 'PENDING' AND deleted_at IS NULL AND scheduled_at <= ?`)
        .bind(token, now, now, item.id, now).run();
      if (update.meta.changes !== 1) continue;
      const row = await this.db.prepare(`${SELECT} WHERE id = ?`).bind(item.id).first<ReminderRow>();
      if (row) claimed.push(map(row));
    }
    return claimed;
  }

  async finish(task: EmailReminderTask, nextScheduledAt: string | null, now: string): Promise<boolean> {
    const recurring = nextScheduledAt !== null;
    const result = await this.db.prepare(`UPDATE email_reminder_task SET
      status = ?, scheduled_at = COALESCE(?, scheduled_at), sent_at = ?,
      send_attempts = CASE WHEN ? = 1 THEN 0 ELSE send_attempts END,
      processing_token = NULL, claimed_at = NULL, error_message = NULL, updated_at = ?
      WHERE id = ? AND status = 'SENDING' AND processing_token = ? AND deleted_at IS NULL`)
      .bind(recurring ? "PENDING" : "SENT", nextScheduledAt, now, recurring ? 1 : 0,
        now, task.id, task.processingToken).run();
    return result.meta.changes === 1;
  }

  async fail(task: EmailReminderTask, message: string, retry: boolean, now: string): Promise<void> {
    await this.db.prepare(`UPDATE email_reminder_task SET status = ?, error_message = ?,
      processing_token = NULL, claimed_at = NULL, updated_at = ?
      WHERE id = ? AND status = 'SENDING' AND processing_token = ? AND deleted_at IS NULL`)
      .bind(retry ? "PENDING" : "FAILED", message.slice(0, 1000), now,
        task.id, task.processingToken).run();
  }
}
