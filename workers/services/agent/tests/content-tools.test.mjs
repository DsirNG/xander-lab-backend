import assert from "node:assert/strict";
import test from "node:test";
import { ContentAgentToolsMapper } from "../.test-build/mappers/content-agent-tools-mapper.js";
import { AgentContentToolsService } from "../.test-build/services/agent-content-tools-service.js";

function mapperWith(respond) {
  const calls = [];
  const mapper = new ContentAgentToolsMapper({
    CONTENT_AUTOMATION_SERVICE_URL: "https://content.example/base",
    CONTENT_AUTOMATION_SERVICE_TOKEN: "internal-secret",
    CONTENT_AUTOMATION_SERVICE: { async fetch(request) {
      calls.push(request);
      return Response.json(await respond(new URL(request.url), request));
    } },
  });
  return { mapper, calls };
}

test("Agent Content tools query owner-projected posts across lifecycle states and filter sync status", async () => {
  const { mapper, calls } = mapperWith((url) => {
    const status = Number(url.searchParams.get("status"));
    const row = status === 0
      ? { id: 1, title: "Draft", status: 0, csdnSynced: false, juejinSynced: false, tags: [] }
      : status === 1
        ? { id: 2, title: "Published", status: 1, csdnSynced: true, juejinSynced: false, tags: ["d1"] }
        : { id: 3, title: "Trashed", status: -1, csdnSynced: false, juejinSynced: false, tags: [] };
    return { code: 200, data: { records: [row], hasMore: false, total: 1 } };
  });
  const result = await mapper.queryPosts(42, { csdnSynced: false, limit: 10 });
  assert.deepEqual(result.data.posts.map((post) => post.id), [3, 1]);
  assert.equal(result.data.total, 2);
  assert.match(result.answer, /Draft/);
  assert.equal(calls.length, 3);
  assert.ok(calls.every((request) => request.headers.get("x-user-id") === "42"));
  assert.ok(calls.every((request) => request.headers.get("x-dinqor-service-token") === "internal-secret"));
});

test("Agent Content tools query plans and reminders through the protected HTTPS service contract", async () => {
  const { mapper, calls } = mapperWith((url) => url.pathname === "/api/blog-plans"
    ? { code: 200, data: { records: [
      { id: 8, topic: "D1 migration", status: "ACTIVE", nextRunAt: "2026-10-11T09:00:00Z", triggerTimes: ["09:00"] },
      { id: 9, topic: "Paused", status: "PAUSED" },
    ], hasMore: false } }
    : { code: 200, data: { records: [
      { id: 4, recipientEmail: "owner@example.com", subject: "Weekly report", status: "PENDING", scheduledAt: "2026-10-12T01:00:00Z" },
    ], hasMore: false } });
  const service = new AgentContentToolsService(mapper);
  const plans = await service.execute(42, "query_plans", { status: "ACTIVE" });
  const reminders = await service.execute(42, "query_email_reminders", { status: "PENDING", search: "report" });
  assert.equal(plans.data.total, 1);
  assert.match(plans.answer, /D1 migration/);
  assert.equal(reminders.data.total, 1);
  assert.match(reminders.answer, /owner@example.com/);
  assert.equal(calls.length, 2);
});

test("Agent query_unsynced_posts defaults to Juejin and never returns another owner’s rows", async () => {
  const { mapper } = mapperWith((url) => {
    const status = Number(url.searchParams.get("status"));
    const row = { id: status + 10, title: `Status ${status}`, status, juejinSynced: false, csdnSynced: false, tags: [] };
    return { code: 200, data: { records: [row], hasMore: false } };
  });
  const result = await mapper.queryUnsyncedPosts(42, {});
  assert.equal(result.data.platform, "juejin");
  assert.equal(result.data.total, 3);
  assert.match(result.answer, /掘金未同步文章共 3 篇/);
});

test("Content tool creation requests carry owner, service authentication, and stable idempotency keys", async () => {
  const received = [];
  const { mapper } = mapperWith(async (url, request) => {
    received.push({ url, method: request.method, body: request.method === "GET" || request.method === "DELETE"
      ? null : await request.clone().json(),
      owner: request.headers.get("x-user-id"), token: request.headers.get("x-dinqor-service-token") });
    return { code: 200, data: url.pathname === "/api/blog-plans"
      ? { id: 18, topic: "D1 的正确用法", status: "ACTIVE" }
      : { id: 19, recipientEmail: "owner@example.com", subject: "周报", status: "PENDING" } };
  });
  const plan = await mapper.createPlan(42, { topic: "D1 的正确用法", triggerTime: "09:30" }, "agent-plan-abc");
  const reminder = await mapper.createEmailReminder(42, {
    recipientEmail: "owner@example.com", subject: "周报", message: "汇总本周进度", scheduledAt: "2026-10-12T09:00:00+08:00",
  }, "agent-mail-abc");

  assert.match(plan.answer, /D1 的正确用法/);
  assert.match(reminder.answer, /owner@example.com/);
  assert.deepEqual(received.map((item) => [item.method, item.owner, item.token]), [
    ["POST", "42", "internal-secret"], ["POST", "42", "internal-secret"],
  ]);
  assert.equal(received[0].body.clientRequestId, "agent-plan-abc");
  assert.deepEqual(received[0].body.triggerTimes, ["09:30"]);
  assert.equal(received[1].body.clientRequestId, "agent-mail-abc");
});

test("Content tools keep status/edit operations owner-scoped and map deletion through DELETE", async () => {
  const received = [];
  const { mapper } = mapperWith(async (url, request) => {
    received.push({ path: url.pathname, method: request.method,
      body: request.method === "GET" || request.method === "DELETE" ? null : await request.clone().json(),
      owner: request.headers.get("x-user-id") });
    return { code: 200, data: request.method === "DELETE" ? null : { id: 8, status: "PAUSED" } };
  });

  await mapper.updatePlan(42, { planId: 8, action: "PAUSE" });
  await mapper.updatePlan(42, { planId: 8, action: "EDIT", topic: "New topic", syncCsdn: false });
  await mapper.updateEmailReminder(42, { taskId: 12, action: "DELETE" });

  assert.deepEqual(received, [
    { path: "/api/blog-plans/8/status", method: "PATCH", body: { action: "PAUSED" }, owner: "42" },
    { path: "/api/blog-plans/8", method: "PATCH", body: { topic: "New topic", syncCsdn: false }, owner: "42" },
    { path: "/api/email-reminders/12", method: "DELETE", body: null, owner: "42" },
  ]);
});
