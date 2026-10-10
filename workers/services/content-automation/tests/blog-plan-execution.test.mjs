import assert from "node:assert/strict";
import test from "node:test";
import { BlogPlanExecutionService } from "../.test-build/services/blog-plan-execution-service.js";

const articlePayload = {
  title: "A D1 migration guide", summary: "How to migrate safely.",
  content: "## Plan\nMigrate schema and verify data.", categoryId: "backend", tags: ["Cloudflare", "D1"],
};

function fixture({ runOnce = false, compliancePass = true } = {}) {
  const plan = {
    id: 9, userId: 42, topic: "Cloudflare D1", audience: "backend developers", tone: "clear",
    scheduleType: runOnce ? "ONCE" : "DAILY", scheduledDate: runOnce ? "2026-10-11" : null,
    aiOption: "DEEP", knowledgeMaterialId: null, autoPublish: true, timezone: "Asia/Singapore",
    triggerTime: "09:00", triggerTimes: ["09:00"], topics: [], nextTopicIndex: 0,
    syncCsdn: true, syncJuejin: false, runOnce, nextRunAt: "2026-10-11T01:00:00.000Z",
    lastRunAt: null, status: runOnce ? "RUNNING" : "ACTIVE", errorMessage: null,
    deletedAt: null, createdAt: "2026-10-10T10:00:00.000Z", updatedAt: "2026-10-10T10:00:00.000Z",
    processingToken: runOnce ? "plan-token" : null,
  };
  const run = {
    id: 77, planId: 9, scheduledAt: "2026-10-11T01:00:00.000Z", triggerType: "MANUAL",
    status: "GENERATING", startedAt: null, finishedAt: null, localPostId: null,
  };
  const updates = [];
  const notifications = [];
  const modelCalls = [];
  let createdPost = null;
  let charged = false;
  const repository = {
    async findById(userId, planId) { return userId === plan.userId && planId === plan.id ? plan : null; },
    async findRun(planId, runId) { return planId === plan.id && runId === run.id ? run : null; },
    async createManualRun() { return run.id; },
    async claimDue() { return []; },
    async createScheduledRun() { return run.id; },
    async consumeNextTopic() { return null; },
    async updateRun(_planId, _runId, patch) { Object.assign(run, patch); updates.push(patch); return true; },
    async finishScheduledPlan(input) { updates.push({ scheduledPlan: input }); return true; },
  };
  const model = {
    async ensureAvailable(userId) { assert.equal(userId, 42); },
    async runStage(stage, input) {
      modelCalls.push({ stage, input });
      const payload = stage === "analyze" ? { angle: "D1 safety" }
        : stage === "research" ? { findings: [], gaps: [] }
        : stage === "article" ? articlePayload
        : stage === "review" ? { content: articlePayload.content, review: "Clear" }
        : { pass: compliancePass, reasons: compliancePass ? [] : ["Unsafe"] };
      return { payloadJson: JSON.stringify(payload), inputTokens: 10, cachedTokens: 1, outputTokens: 5, model: "plan-model" };
    },
    async chargeTokens(input) { charged = input; },
  };
  const posts = { async create(input) { createdPost = input; return { id: 123 }; } };
  const publications = {
    async sync(userId, postId, platform) {
      assert.deepEqual([userId, postId, platform], [42, 123, "csdn"]);
      return { platform, externalId: "external-1", url: "https://csdn.example/post/1" };
    },
  };
  const notifier = { async create(input) { notifications.push(input); } };
  const service = new BlogPlanExecutionService(repository, model, posts, publications, notifier, undefined,
    () => Date.parse("2026-10-11T02:00:00.000Z"));
  return { plan, run, updates, notifications, modelCalls, get createdPost() { return createdPost; },
    get charged() { return charged; }, service };
}

test("manual plan run analyzes, researches, reviews, checks compliance, publishes, and bills usage", async () => {
  const state = fixture();
  await state.service.executeManual(42, 9, 77);
  assert.deepEqual(state.modelCalls.map(({ stage }) => stage), ["analyze", "research", "article", "review", "compliance"]);
  assert.match(state.modelCalls[2].input.input, /策划结果：/);
  assert.equal(state.createdPost.idempotencyKey, "blog-plan-run-77");
  assert.equal(state.createdPost.publish, true);
  assert.equal(state.charged.runId, 77);
  assert.deepEqual(state.charged.usage, { inputTokens: 50, cachedTokens: 5, outputTokens: 25 });
  assert.equal(state.run.status, "SUCCEEDED");
  assert.equal(state.run.localPostId, 123);
  assert.equal(state.run.csdnStatus, "SUCCEEDED");
  assert.equal(state.notifications.length, 0);
});

test("failed compliance stops publication and records a rejected run", async () => {
  const state = fixture({ compliancePass: false });
  await state.service.executeManual(42, 9, 77);
  assert.equal(state.run.status, "REVIEW_REJECTED");
  assert.equal(state.run.reviewPass, false);
  assert.equal(state.createdPost, null);
  assert.equal(state.charged, false);
  assert.equal(state.notifications[0].type, "REVIEW_REJECTED");
  assert.equal(state.updates.some((patch) => patch.scheduledPlan), false);
});

test("scheduled one-shot run claims a due plan and settles it as finished on success", async () => {
  const state = fixture({ runOnce: true });
  state.run.triggerType = "SCHEDULED";
  state.run.status = "GENERATING";
  const repo = {
    async claimDue() { return [state.plan]; },
    async createScheduledRun() { return state.run.id; },
    async findRun() { return state.run; },
    async findById() { return state.plan; },
    async consumeNextTopic() { return null; },
    async updateRun(_planId, _runId, patch) { Object.assign(state.run, patch); return true; },
    async finishScheduledPlan(input) { state.updates.push({ scheduledPlan: input }); return true; },
  };
  const model = {
    async ensureAvailable() {},
    async runStage(stage) {
      const payload = stage === "analyze" ? { angle: "D1 safety" }
        : stage === "research" ? { findings: [] }
        : stage === "article" ? articlePayload
        : stage === "review" ? { content: articlePayload.content }
        : { pass: true, reasons: [] };
      return { payloadJson: JSON.stringify(payload), inputTokens: 1, cachedTokens: 0, outputTokens: 1, model: "plan-model" };
    },
    async chargeTokens() {},
  };
  const service = new BlogPlanExecutionService(repo, model,
    { async create() { return { id: 123 }; } },
    { async sync() { return { platform: "csdn", externalId: "x", url: null }; } },
    { async create() {} }, undefined, () => Date.parse("2026-10-11T02:00:00.000Z"));
  await service.processDue(Date.parse("2026-10-11T01:00:00.000Z"));
  assert.equal(state.run.status, "SUCCEEDED");
  const settled = state.updates.find((patch) => patch.scheduledPlan)?.scheduledPlan;
  assert.equal(settled.status, "FINISHED");
  assert.equal(settled.nextRunAt, null);
});
