import assert from "node:assert/strict";
import test from "node:test";
import { DatabaseSync } from "node:sqlite";
import { readFileSync } from "node:fs";
import { D1EmailReminderMapper } from "../.test-build/mappers/d1-email-reminder-mapper.js";
import { EmailReminderService } from "../.test-build/services/email-reminder-service.js";
import { handleEmailReminderRequest } from "../.test-build/controllers/email-reminder-controller.js";
import worker from "../.test-build/index.js";

const now = "2026-11-14T05:30:00.000Z";
const input = { clientRequestId: "approved-mail-1", recipientEmail: "owner@example.test", subject: "问候", message: "你在干嘛" };

function setup(t, deliver = async () => true) {
  const sql = new DatabaseSync(":memory:");
  t.after(() => sql.close());
  sql.exec(readFileSync(new URL("../migrations/0010_email_reminders.sql", import.meta.url), "utf8"));
  // Exercise the upgrade with an existing historical reminder.
  sql.exec("INSERT INTO email_reminder_task (user_id, client_request_id, recipient_email, subject, message, scheduled_at, status, sent_at, created_at, updated_at) VALUES (42, 'old', 'owner@example.test', '记得下班', '旧邮件', '2026-07-24T10:30:00.000Z', 'SENT', '2026-07-24T10:30:00.000Z', '2026-07-24T10:00:00.000Z', '2026-07-24T10:30:00.000Z')");
  sql.exec(readFileSync(new URL("../migrations/0016_immediate_email.sql", import.meta.url), "utf8"));
  const db = { prepare(query) {
    let args = [];
    const statement = {
      bind(...values) { args = values; return statement; },
      async first() { return sql.prepare(query).get(...args) ?? null; },
      async all() { return { results: sql.prepare(query).all(...args) }; },
      async run() { return { meta: { changes: Number(sql.prepare(query).run(...args).changes) } }; },
    };
    return statement;
  } };
  const repository = new D1EmailReminderMapper(db);
  const messages = [], charges = [];
  const service = new EmailReminderService(repository, {
    async send(...args) {
      messages.push(args);
      return deliver(...args);
    },
  }, { async flatPoints() { return 5000; }, async charge(value) { charges.push(value); } },
  { senderEmail: "noreply@example.test", minimumLeadSeconds: 30 }, () => new Date(now));
  return { sql, db, repository, messages, charges, service };
}

function request(body, userId = "42") {
  return new Request("https://content.test/internal/content-automation/mail/send", {
    method: "POST", headers: { "content-type": "application/json", ...(userId ? { "x-user-id": userId } : {}) },
    body: JSON.stringify(body),
  });
}

test("immediate email persists this exact request and replay never sends the old reminder or duplicates it", async (t) => {
  const { service, repository, messages, charges } = setup(t);
  const response = await handleEmailReminderRequest(request(input), service);
  assert.equal(response.status, 200);
  const { data } = await response.json();
  assert.equal(data.deliveryMode, "IMMEDIATE");
  assert.equal(data.status, "SENT");
  assert.equal(data.clientRequestId, input.clientRequestId);
  assert.equal(data.message, "你在干嘛");
  assert.equal(data.scheduledAt, now);
  assert.equal(data.sentAt, now);
  assert.equal(data.processingToken, undefined);
  assert.equal(messages.length, 1);
  assert.deepEqual(messages[0], ["owner@example.test", "问候", "你在干嘛", null]);
  assert.equal(charges.length, 1);
  assert.equal((await repository.findMine(42, 1)).deliveryMode, "SCHEDULED");
  const replay = await service.sendImmediate(42, input);
  assert.equal(replay.id, data.id);
  assert.equal(replay.status, "SENT");
  await service.processDue(new Date(now));
  assert.equal(messages.length, 1);
  assert.equal(charges.length, 1);
});

test("concurrent replays atomically claim one immediate task, even while the provider is pending", async (t) => {
  let release;
  const provider = new Promise(resolve => { release = resolve; });
  const { service, messages, sql } = setup(t, () => provider);
  const first = service.sendImmediate(42, input);
  const second = service.sendImmediate(42, input);
  const stillSending = await second;
  assert.equal(stillSending.status, "SENDING");
  assert.equal(messages.length, 1);
  await service.processDue(new Date(now));
  assert.equal(messages.length, 1);
  release(true);
  assert.equal((await first).status, "SENT");
  assert.equal(sql.prepare("SELECT COUNT(*) AS n FROM email_reminder_task WHERE delivery_mode = 'IMMEDIATE'").get().n, 1);
});

test("same idempotency key rejects changed approved content or a historical scheduled task", async (t) => {
  const { service, messages } = setup(t);
  await service.sendImmediate(42, input);
  for (const changed of [{ message: "另一封邮件" }, { subject: "其他主题" }, { recipientEmail: "other@example.test" },
    { contentType: "HTML" }, { clientRequestId: "old", subject: "记得下班", message: "旧邮件" }]) {
    await assert.rejects(service.sendImmediate(42, { ...input, ...changed }), error => error.status === 409);
  }
  assert.equal(messages.length, 1);
});

test("uncertain delivery is terminal and never retried through replay, cron, or resume", async (t) => {
  const { service, messages, charges } = setup(t, async () => { throw new Error("provider timeout"); });
  const failed = await service.sendImmediate(42, input);
  assert.equal(failed.status, "FAILED");
  assert.equal(failed.sendAttempts, 1);
  assert.equal(failed.sentAt, null);
  await service.sendImmediate(42, input);
  await service.processDue(new Date(Date.parse(now) + 60_000));
  await assert.rejects(service.updateStatus(42, failed.id, "PENDING"), error => error.status === 409);
  assert.equal(messages.length, 1);
  assert.equal(charges.length, 0);
});

test("a lost persistence acknowledgement after provider acceptance cannot resend the immediate email", async (t) => {
  const { service, repository, messages, charges } = setup(t);
  repository.finish = async () => { throw new Error("D1 unavailable"); };
  const result = await service.sendImmediate(42, input);
  assert.equal(result.status, "FAILED");
  await service.sendImmediate(42, input);
  await service.processDue(new Date(Date.parse(now) + 60_000));
  assert.equal(messages.length, 1);
  assert.equal(charges.length, 0);
});

test("cron recovers an unclaimed immediate task after interruption before the claim", async (t) => {
  const { service, repository, messages } = setup(t);
  const claim = repository.claimImmediate.bind(repository);
  repository.claimImmediate = async () => { throw new Error("request interrupted before claim"); };
  await assert.rejects(service.sendImmediate(42, input));
  assert.equal(messages.length, 0);
  repository.claimImmediate = claim;
  await service.processDue(new Date(now));
  assert.equal(messages.length, 1);
  await service.sendImmediate(42, input);
  assert.equal(messages.length, 1);
});

test("stale immediate claims stop for reconciliation instead of being resent", async (t) => {
  const { service, sql, messages } = setup(t);
  const result = await service.sendImmediate(42, input);
  sql.prepare("UPDATE email_reminder_task SET status = 'SENDING', processing_token = 'lost', claimed_at = ?, sent_at = NULL WHERE id = ?")
    .run("2026-11-14T05:00:00.000Z", result.id);
  await service.processDue(new Date(now));
  assert.equal((await service.sendImmediate(42, input)).status, "FAILED");
  assert.equal(messages.length, 1);
});

test("immediate mail enforces identity, idempotency, content validation, owner separation and quota", async (t) => {
  const { service, repository, messages } = setup(t);
  const anonymous = await handleEmailReminderRequest(request(input, null), service);
  assert.equal(anonymous.status, 401);
  assert.equal((await anonymous.json()).code, 401);
  for (const bad of [{ clientRequestId: "" }, { recipientEmail: "not-an-email" }, { subject: "inject\r\nBcc: x" },
    { message: "" }, { scheduledAt: now }, { userId: 99 }, { contentType: "SCRIPT" }]) {
    const response = await handleEmailReminderRequest(request({ ...input, ...bad }), service);
    assert.equal(response.status, 400);
    assert.equal((await response.json()).code, 400);
  }
  const mine = await service.sendImmediate(42, input);
  const theirs = await service.sendImmediate(99, input);
  assert.notEqual(mine.id, theirs.id);
  assert.equal(await repository.findMine(99, mine.id), null);
  await assert.rejects(service.delete(99, mine.id), error => error.status === 404);
  for (let n = 1; n < 20; n++) await service.sendImmediate(42, { ...input, clientRequestId: "quota-" + n });
  await assert.rejects(service.sendImmediate(42, { ...input, clientRequestId: "over-quota" }), error => error.status === 429);
  assert.equal(messages.length, 21);
});

test("Content immediate route rejects missing service auth before touching persistence", async () => {
  const response = await worker.fetch(request(input), {
    INTERNAL_SERVICE_TOKEN: "content-test-token",
    DB: { prepare() { throw new Error("unauthenticated requests must not reach DB"); } },
  }, { waitUntil() {} });
  assert.equal(response.status, 401);
  assert.equal((await response.json()).code, 401);
});

test("existing scheduled reminders retain the future lead-time requirement", async (t) => {
  const { service, messages } = setup(t);
  await assert.rejects(service.create(42, { ...input, scheduledAt: now }), error => error.status === 400);
  assert.equal(messages.length, 0);
});
