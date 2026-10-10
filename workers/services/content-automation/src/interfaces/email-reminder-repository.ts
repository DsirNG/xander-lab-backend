export type EmailReminderStatus = "PENDING" | "PAUSED" | "SENDING" | "SENT" | "FAILED";
export type EmailReminderFrequency = "ONCE" | "DAILY" | "WEEKLY" | "MONTHLY" | "CUSTOM";

export interface EmailReminderTask {
  id: number;
  userId: number;
  clientRequestId: string;
  recipientEmail: string;
  subject: string;
  message: string;
  contentType: "PLAIN" | "HTML";
  templateId: string;
  frequency: EmailReminderFrequency;
  timezone: string;
  sendTime: string | null;
  recurrenceDay: number | null;
  intervalDays: number | null;
  scheduledAt: string;
  status: EmailReminderStatus;
  sendAttempts: number;
  errorMessage: string | null;
  processingToken: string | null;
  sentAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface EmailReminderCreateInput extends Omit<EmailReminderTask,
  "id" | "status" | "sendAttempts" | "errorMessage" | "processingToken" | "sentAt" | "createdAt" | "updatedAt"> {
  now: string;
}

export interface EmailReminderPage {
  records: EmailReminderTask[];
  total: number;
  current: number;
  pages: number;
  size: number;
  hasMore: boolean;
  stats: { total: number; active: number; sent: number; pending: number };
}

export interface EmailReminderRepository {
  create(input: EmailReminderCreateInput): Promise<EmailReminderTask | { kind: "quota" | "deleted-replay" }>;
  findMine(userId: number, taskId: number): Promise<EmailReminderTask | null>;
  listMine(input: { userId: number; status: string | null; search: string; page: number; size: number }): Promise<EmailReminderPage>;
  updateStatus(userId: number, taskId: number, status: "PENDING" | "PAUSED", scheduledAt: string | null, now: string): Promise<boolean>;
  softDelete(userId: number, taskId: number, now: string): Promise<"deleted" | "sending" | "missing">;
  claimDue(now: string, staleBefore: string, retryBefore: string, limit: number): Promise<EmailReminderTask[]>;
  finish(task: EmailReminderTask, nextScheduledAt: string | null, now: string): Promise<boolean>;
  fail(task: EmailReminderTask, message: string, retry: boolean, now: string): Promise<void>;
}

export interface EmailReminderMailPort {
  send(email: string, subject: string, text: string, html: string | null): Promise<boolean>;
}

export interface EmailReminderBillingPort {
  flatPoints(bizType: string): Promise<number>;
  charge(input: { userId: number; bizType: string; bizKey: string; bizRef: string; points: number; remark: string }): Promise<void>;
}
