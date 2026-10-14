import assert from "node:assert/strict";
import test from "node:test";
import { handleBlogPlanRequest } from "../.test-build/controllers/blog-plan-controller.js";
import { BlogPlanError, BlogPlanService } from "../.test-build/services/blog-plan-service.js";

function setup(now = Date.parse("2026-10-10T10:00:00.000Z"), materials, topics) {
  const plans = [];
  let nextId = 1;
  const repository = {
    async findById(userId, id) { return plans.find((row) => row.userId === userId && row.id === id && !row.deletedAt) ?? null; },
    async findByRequestId(userId, requestId) { return plans.find((row) => row.userId === userId && row.clientRequestId === requestId) ?? null; },
    async list(userId, limit, offset) { return plans.filter((row) => row.userId === userId && !row.deletedAt).slice().reverse().slice(offset, offset + limit); },
    async count(userId) { return plans.filter((row) => row.userId === userId && !row.deletedAt).length; },
    async countActive(userId) { return plans.filter((row) => row.userId === userId && !row.deletedAt && ["ACTIVE", "RUNNING", "PAUSED"].includes(row.status)).length; },
    async countCreatedSince(userId, since) { return plans.filter((row) => row.userId === userId && row.createdAt >= since).length; },
    async create(input) {
      const row = { ...input, id: nextId++, createdAt: new Date(now).toISOString(), updatedAt: new Date(now).toISOString() };
      plans.push(row);
      return row.id;
    },
    async createMany(inputs) {
      const ids = [];
      for (const input of inputs) ids.push(await this.create(input));
      return ids;
    },
    async update(plan, expectedStatus) {
      const row = plans.find((item) => item.id === plan.id && item.userId === plan.userId && item.status === expectedStatus && !item.deletedAt);
      if (!row) return false;
      Object.assign(row, plan, { updatedAt: new Date(now).toISOString() });
      return true;
    },
    async setStatus(userId, id, from, status, values = {}) {
      const row = plans.find((item) => item.id === id && item.userId === userId && !item.deletedAt && from.includes(item.status));
      if (!row) return false;
      Object.assign(row, values, { status, updatedAt: new Date(now).toISOString() });
      return true;
    },
    async softDelete(userId, id, deletedAt) {
      const row = plans.find((item) => item.id === id && item.userId === userId && !item.deletedAt);
      if (!row) return false;
      row.deletedAt = deletedAt;
      return true;
    },
    async listRuns() { return []; },
    async countRuns() { return 0; },
    async findRun() { return null; },
  };
  const service = new BlogPlanService(repository, { now: () => now, materials, topics });
  return { plans, repository, service };
}

const dailyPlan = {
  clientRequestId: "plan-create-1",
  topic: "Cloudflare D1 migrations",
  scheduleType: "DAILY",
  timezone: "Asia/Singapore",
  triggerTimes: ["19:30", "18:00", "19:30"],
  topics: ["D1 schema", "Worker routing", "D1 schema"],
  syncCsdn: true,
};

test("blog plan create is idempotent, normalizes schedules, and scopes reads by owner", async () => {
  const { plans, service } = setup();
  const created = await service.create(42, dailyPlan);
  assert.equal(created.topic, dailyPlan.topic);
  assert.deepEqual(created.triggerTimes, ["18:00", "19:30"]);
  assert.deepEqual(created.topics, ["D1 schema", "Worker routing"]);
  assert.equal(created.status, "ACTIVE");
  assert.equal(created.nextRunAt, "2026-10-10T11:30:00.000Z");
  const retry = await service.create(42, dailyPlan);
  assert.equal(retry.id, created.id);
  assert.equal(plans.length, 1);
  await assert.rejects(() => service.detail(7, created.id), (error) => error instanceof BlogPlanError && error.status === 404);
});

test("blog plan supports once and weekly scheduling, pause/resume, edit, and soft deletion", async () => {
  const { plans, service } = setup();
  const once = await service.create(42, {
    topic: "One-time article", scheduleType: "ONCE", scheduledDate: "2026-10-11",
    timezone: "Asia/Singapore", triggerTime: "09:00",
  });
  assert.equal(once.nextRunAt, "2026-10-11T01:00:00.000Z");
  const weekly = await service.create(42, {
    topic: "Weekly article", scheduleType: "WEEKLY", scheduledDate: "2026-10-11",
    timezone: "Asia/Singapore", triggerTime: "09:00",
  });
  assert.equal(weekly.nextRunAt, "2026-10-11T01:00:00.000Z");

  const paused = await service.updateStatus(42, weekly.id, "PAUSED");
  assert.equal(paused.status, "PAUSED");
  const resumed = await service.updateStatus(42, weekly.id, "RESUME");
  assert.equal(resumed.status, "ACTIVE");
  const edited = await service.update(42, weekly.id, { topic: "Updated weekly article", triggerTime: "10:00" });
  assert.equal(edited.topic, "Updated weekly article");
  assert.equal(edited.triggerTime, "10:00");
  await service.delete(42, once.id);
  assert.equal(plans.find((row) => row.id === once.id).deletedAt, "2026-10-10T10:00:00.000Z");
  await assert.rejects(() => service.detail(42, once.id), (error) => error.status === 404);
});

test("plan inputs reject invalid schedules and check Core-owned recitation materials", async () => {
  const materials = { async checkOwned(owner, id) { return owner === 42 && id === 9 ? "owned" : "not_found"; } };
  const { service } = setup(undefined, materials);
  await assert.rejects(() => service.create(42, { ...dailyPlan, timezone: "Unknown/Zone" }), /时区无效/);
  await assert.rejects(() => service.create(42, { ...dailyPlan, knowledgeMaterialId: 10 }), (error) => error.status === 404);
  const valid = await service.create(42, { ...dailyPlan, clientRequestId: "with-material", knowledgeMaterialId: 9 });
  assert.equal(valid.knowledgeMaterialId, 9);
});

test("blog plan controller keeps the API envelope and enforces the projected Gateway owner", async () => {
  const { service } = setup();
  const anonymous = await handleBlogPlanRequest(new Request("https://content/api/blog-plans"), service);
  assert.equal(anonymous.status, 401);
  const created = await handleBlogPlanRequest(new Request("https://content/api/blog-plans", {
    method: "POST", headers: { "x-user-id": "42", "content-type": "application/json" },
    body: JSON.stringify(dailyPlan),
  }), service);
  assert.equal(created.status, 200);
  assert.equal((await created.json()).data.userId, undefined);
});

test("topic generation excludes existing plan topics and deduplicates model output", async () => {
  let request;
  const { service } = setup(undefined, undefined, {
    async generateTopics(userId, input) {
      request = { userId, input };
      return ["A focused D1 migration", "Cloudflare D1 migrations", "a focused d1 migration", "A Worker routing guide"];
    },
  });
  await service.create(42, dailyPlan);
  const topics = await service.generateTopics(42, {
    topic: "Cloudflare D1 migrations", count: 5, audience: "backend developers",
  });
  assert.deepEqual(topics, ["A focused D1 migration", "A Worker routing guide"]);
  assert.equal(request.userId, 42);
  assert.ok(request.input.excludeTitles.includes("D1 schema"));
  assert.ok(request.input.excludeTitles.includes(dailyPlan.topic));
  await assert.rejects(service.generateTopics(42, { topic: "D1", count: 31 }),
    (error) => error instanceof BlogPlanError && error.status === 400);
});

test("topic generation controller returns the legacy success envelope", async () => {
  const { service } = setup(undefined, undefined, {
    async generateTopics() { return ["D1 schema evolution", "D1 query design"]; },
  });
  const response = await handleBlogPlanRequest(new Request("https://content/api/blog-plans/topics/generate", {
    method: "POST", headers: { "x-user-id": "42", "content-type": "application/json" },
    body: JSON.stringify({ topic: "Cloudflare D1", count: 2 }),
  }), service);
  assert.equal(response.status, 200);
  assert.deepEqual((await response.json()).data, ["D1 schema evolution", "D1 query design"]);
});

test("AI plan generation creates one-shot D1 plans on consecutive local dates", async () => {
  let request;
  const { plans, service } = setup(undefined, undefined, {
    async generateTopics(userId, input) {
      request = { userId, input };
      return ["D1 migration safety", "Worker data ownership"];
    },
  });
  const result = await service.aiGenerate(42, {
    topic: "Cloudflare D1", days: 2, time: "09:00", timezone: "Asia/Singapore",
    audience: "backend developers", syncCsdn: true,
  });
  assert.equal(result.length, 2);
  assert.deepEqual(plans.map((plan) => [plan.topic, plan.scheduleType, plan.scheduledDate]), [
    ["D1 migration safety", "ONCE", "2026-10-11"],
    ["Worker data ownership", "ONCE", "2026-10-12"],
  ]);
  assert.ok(plans.every((plan) => plan.autoPublish && plan.syncCsdn && plan.runOnce));
  assert.equal(request.userId, 42);
  assert.ok(request.input.excludeTitles.includes("Cloudflare D1"));
  await assert.rejects(service.aiGenerate(42, { topic: "D1", days: 31 }),
    (error) => error instanceof BlogPlanError && error.status === 400);
});

test("AI plan-generation endpoint returns the generated plan list envelope", async () => {
  const { service } = setup(undefined, undefined, {
    async generateTopics() { return ["D1 migration safety"]; },
  });
  const response = await handleBlogPlanRequest(new Request("https://content/api/blog-plans/ai-generate", {
    method: "POST", headers: { "x-user-id": "42", "content-type": "application/json" },
    body: JSON.stringify({ topic: "Cloudflare D1", days: 1 }),
  }), service);
  assert.equal(response.status, 200);
  const payload = await response.json();
  assert.equal(payload.data.length, 1);
  assert.equal(payload.data[0].topic, "D1 migration safety");
  assert.equal(payload.data[0].userId, undefined);
});

test("manual trigger creates a run and schedules execution in the Worker background context", async () => {
  const { service } = setup();
  let background;
  const run = { id: 77, planId: 9, triggerType: "MANUAL", status: "GENERATING" };
  const execution = {
    async trigger(userId, planId) { assert.deepEqual([userId, planId], [42, 9]); return run; },
    async executeManual(userId, planId, runId) { background = [userId, planId, runId]; },
  };
  const response = await handleBlogPlanRequest(new Request("https://content/api/blog-plans/9/trigger", {
    method: "POST", headers: { "x-user-id": "42" },
  }), service, execution, { waitUntil(promise) { void promise; } });
  assert.equal(response.status, 200);
  assert.deepEqual((await response.json()).data, run);
  await new Promise((resolve) => setTimeout(resolve, 0));
  assert.deepEqual(background, [42, 9, 77]);
});


test("confirmed 30-day draft preserves every topic without calling AI", async () => {
  const { service, plans } = setup();
  const topics = Array.from({ length: 30 }, (_, i) => `Frontend topic ${i + 1}`);
  const result = await service.aiGenerate(42, { topic: "Frontend", days: 30, topics });
  assert.equal(result.length, 30);
  assert.deepEqual(result.map((plan) => plan.topic), topics);
  assert.ok(plans.every((plan) => plan.runOnce && plan.scheduleType === "ONCE"));
  assert.equal(plans[29].scheduledDate, "2026-11-09");
});

test("incomplete, blank or duplicate daily topics never create partial plans", async () => {
  for (const topics of [["Only one"], ["Same", "same"], ["One", " "]]) {
    const { service, plans } = setup();
    await assert.rejects(service.aiGenerate(42, { topic: "Frontend", days: 2, topics }),
      (error) => error instanceof BlogPlanError && error.status === 400);
    assert.equal(plans.length, 0);
  }
  const { service, plans } = setup(undefined, undefined, { async generateTopics() { return ["Only one"]; } });
  await assert.rejects(service.aiGenerate(42, { topic: "Frontend", days: 30 }),
    (error) => error.status === 400);
  assert.equal(plans.length, 0);
});
