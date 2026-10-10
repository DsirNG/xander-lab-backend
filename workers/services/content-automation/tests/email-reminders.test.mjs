import assert from "node:assert/strict";
import test from "node:test";
import { handleEmailReminderRequest } from "../.test-build/controllers/email-reminder-controller.js";
import { EmailReminderService } from "../.test-build/services/email-reminder-service.js";

function setup(now = "2026-10-10T10:00:00.000Z") {
  const rows = new Map();
  const messages = [];
  const charges = [];
  let id = 1;
  const repository = {
    async create(input) {
      const existing = [...rows.values()].find((row) => row.userId === input.userId && row.clientRequestId === input.clientRequestId);
      if (existing) return existing.deletedAt ? { kind: "deleted-replay" } : existing;
      const row = { ...input, id: id++, status: "PENDING", sendAttempts: 0, errorMessage: null,
        processingToken: null, sentAt: null, createdAt: input.now, updatedAt: input.now, deletedAt: null };
      rows.set(row.id, row);
      return row;
    },
    async findMine(userId, taskId) {
      const row = rows.get(taskId);
      return row?.userId === userId && !row.deletedAt ? row : null;
    },
    async listMine(input) {
      let values = [...rows.values()].filter((row) => row.userId === input.userId && !row.deletedAt);
      if (input.status) values = values.filter((row) => row.status === input.status);
      if (input.search) values = values.filter((row) => row.subject.includes(input.search) || row.recipientEmail.includes(input.search));
      const total = values.length;
      const pages = total ? Math.ceil(total / input.size) : 0;
      const records = values.slice((input.page - 1) * input.size, input.page * input.size);
      const pending = values.filter((row) => row.status === "PENDING").length;
      const sent = values.filter((row) => row.status === "SENT").length;
      return { records, total, current: input.page, size: input.size, pages, hasMore: input.page < pages,
        stats: { total, active: pending, pending, sent } };
    },
    async updateStatus(userId, taskId, status, scheduledAt, changedAt) {
      const row = await this.findMine(userId, taskId);
      if (!row) return false;
      row.status = status;
      if (scheduledAt) row.scheduledAt = scheduledAt;
      row.updatedAt = changedAt;
      return true;
    },
    async softDelete(userId, taskId, changedAt) {
      const row = await this.findMine(userId, taskId);
      if (!row) return "missing";
      if (row.status === "SENDING") return "sending";
      row.deletedAt = changedAt;
      return "deleted";
    },
    async claimDue() { return []; },
    async finish(task, nextScheduledAt, changedAt) {
      const row = rows.get(task.id);
      if (!row || row.processingToken !== task.processingToken) return false;
      row.status = nextScheduledAt ? "PENDING" : "SENT";
      row.scheduledAt = nextScheduledAt ?? row.scheduledAt;
      row.sentAt = changedAt;
      row.processingToken = null;
      return true;
    },
    async fail(task, message, retry, changedAt) {
      const row = rows.get(task.id);
      if (row?.processingToken !== task.processingToken) return;
      row.status = retry ? "PENDING" : "FAILED";
      row.errorMessage = message;
      row.processingToken = null;
      row.updatedAt = changedAt;
    },
  };
  const mail = {
    async send(email, subject, text, html) { messages.push({ email, subject, text, html }); return true; },
  };
  const billing = {
    async flatPoints(type) { assert.equal(type, "EMAIL_REMINDER"); return 5000; },
    async charge(input) { charges.push(input); },
  };
  const service = new EmailReminderService(repository, mail, billing,
    { senderEmail: "noreply@example.test", minimumLeadSeconds: 30, displayZone: "Asia/Singapore" },
    () => new Date(now));
  return { rows, messages, charges, repository, service };
}

function request(path, { method = "GET", userId = "42", body, search = "" } = {}) {
  return new Request("https://content.test" + path + search, {
    method,
    headers: { ...(userId ? { "x-user-id": userId } : {}), ...(body ? { "content-type": "application/json" } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
}

test("email reminder create is owner-authenticated, idempotent and list filters preserve stats", async () => {
  const { service } = setup();
  const body = { clientRequestId: "reminder-1", recipientEmail: " User@example.test ", subject: "Review", message: "Check the draft",
    frequency: "ONCE", scheduledAt: "2026-10-10T10:10:00.000Z" };
  const missingIdentity = await handleEmailReminderRequest(request("/api/email-reminders", { method: "POST", userId: null, body }), service);
  assert.equal(missingIdentity.status, 401);
  const createdResponse = await handleEmailReminderRequest(request("/api/email-reminders", { method: "POST", body }), service);
  const created = await createdResponse.json();
  assert.equal(created.data.recipientEmail, "user@example.test");
  assert.equal(created.data.senderEmail, "noreply@example.test");
  const retriedResponse = await handleEmailReminderRequest(request("/api/email-reminders", { method: "POST", body }), service);
  assert.equal((await retriedResponse.json()).data.id, created.data.id);
  const listResponse = await handleEmailReminderRequest(request("/api/email-reminders", { search: "?status=scheduled&page=1&size=5" }), service);
  const list = await listResponse.json();
  assert.equal(list.data.total, 1);
  assert.equal(list.data.stats.pending, 1);
});

test("email reminder recurrence validates timezones and calculates wall-clock schedules", async () => {
  const { service } = setup("2026-10-10T14:55:00.000Z");
  const daily = await service.create(42, {
    recipientEmail: "user@example.test", subject: "Daily", message: "hello",
    frequency: "DAILY", timezone: "Asia/Singapore", sendTime: "23:00",
  });
  assert.equal(daily.scheduledAt, "2026-10-10T15:00:00.000Z");
  await assert.rejects(service.create(42, {
    recipientEmail: "user@example.test", subject: "Bad", message: "hello",
    frequency: "WEEKLY", timezone: "No/Such_Zone", sendTime: "08:00", recurrenceDay: 1,
  }), (error) => error.status === 400);
  await assert.rejects(service.create(42, {
    recipientEmail: "user@example.test", subject: "Bad", message: "hello",
    frequency: "WEEKLY", timezone: "UTC", sendTime: "08:00", recurrenceDay: 8,
  }), (error) => error.status === 400);
});

test("email reminder can pause, resume and soft-delete, while enforcing owner scope", async () => {
  const { service, rows } = setup();
  const created = await service.create(42, {
    clientRequestId: "task-status", recipientEmail: "user@example.test", subject: "Review", message: "Check",
    frequency: "ONCE", scheduledAt: "2026-10-10T10:10:00.000Z",
  });
  const pausedResponse = await handleEmailReminderRequest(request("/api/email-reminders/" + created.id + "/status", {
    method: "PATCH", body: { status: "PAUSED" },
  }), service);
  assert.equal((await pausedResponse.json()).data.status, "PAUSED");
  const resumedResponse = await handleEmailReminderRequest(request("/api/email-reminders/" + created.id + "/status", {
    method: "PATCH", body: { status: "PENDING" },
  }), service);
  assert.equal((await resumedResponse.json()).data.status, "PENDING");
  const forbidden = await handleEmailReminderRequest(request("/api/email-reminders/" + created.id, {
    method: "DELETE", userId: "99",
  }), service);
  assert.equal(forbidden.status, 404);
  const deleted = await handleEmailReminderRequest(request("/api/email-reminders/" + created.id, { method: "DELETE" }), service);
  assert.equal(deleted.status, 200);
  assert.equal(rows.get(created.id).deletedAt !== null, true);
});

test("due reminders render safe HTML and charge only after a fenced delivery succeeds", async () => {
  const { service, messages, charges, rows, repository } = setup("2026-10-10T10:10:00.000Z");
  const task = await service.create(42, {
    clientRequestId: "send-me", recipientEmail: "user@example.test", subject: "<Reminder>", message: "<script>alert(1)</script><p onclick='x()'>hello</p>",
    contentType: "HTML", templateId: "card", scheduledAt: "2026-10-10T10:09:00.000Z",
  }).catch((error) => error);
  assert.equal(task.status, 400, "one-shot tasks must be scheduled in the future");
  const future = await service.create(42, {
    clientRequestId: "send-me", recipientEmail: "user@example.test", subject: "<Reminder>", message: "<script>alert(1)</script><p onclick='x()'>hello</p>",
    contentType: "HTML", templateId: "card", scheduledAt: "2026-10-10T10:11:00.000Z",
  });
  const stored = rows.get(future.id);
  stored.status = "SENDING";
  stored.processingToken = "lease";
  stored.sendAttempts = 1;
  repository.claimDue = async () => [{ ...stored }];
  await service.processDue(new Date("2026-10-10T10:10:00.000Z"));
  assert.match(messages[0].html, /hello/);
  assert.doesNotMatch(messages[0].html, /script|onclick/i);
  assert.equal(rows.get(future.id).status, "SENT");
  assert.equal(charges.length, 1);
  assert.match(charges[0].bizKey, /^email:\d+:\d+$/);
});
