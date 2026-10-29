import assert from "node:assert/strict";
import test from "node:test";
import { handleBlogAgentTaskRequest } from "../.test-build/controllers/blog-agent-task-controller.js";
import { BlogAgentTaskService } from "../.test-build/services/blog-agent-task-service.js";

function setup(options = {}) {
  const tasks = [];
  const events = [];
  const storage = new Map();
  let nextId = 1;
  let nextEvent = 1;
  const repository = {
    async create(userId, input, clientRequestId, now, generationMode) {
      const task = { id: nextId++, userId, clientRequestId, input, generationMode, inputType: "topic", audience: "", tone: "",
        status: "created", stage: "analyze", title: null, summary: null, content: null, outline: null,
        contentBoundary: "{}", knowledgeGraphJson: "{}", categoryId: "career", tagsJson: "[]", review: null,
        illustrationStatus: "none", illustrationError: null, publishedPostId: null, errorMessage: null,
        createdAt: now, updatedAt: now, sources: [], versions: [], messages: [] };
      tasks.push(task);
      task.messages.push({ id: 1, taskId: task.id, role: "user", kind: "message", stage: null, content: input, createdAt: now });
      return task.id;
    },
    async findById(userId, id) { return tasks.find((task) => task.userId === userId && task.id === id) ?? null; },
    async findByRequestId(userId, requestId) { return tasks.find((task) => task.userId === userId && task.clientRequestId === requestId) ?? null; },
    async list(userId) { return tasks.filter((task) => task.userId === userId).slice().reverse(); },
    async hydrate(userId, id) {
      const task = await this.findById(userId, id);
      if (!task) return null;
      return { task: { ...task }, tags: JSON.parse(task.tagsJson), contentBoundary: {}, knowledgeGraph: {},
        sources: [...task.sources], versions: task.versions.map((version) => ({ ...version })),
        illustrations: [], messages: [...task.messages] };
    },
    async claim(userId, id, mode, now) {
      const task = await this.findById(userId, id);
      if (!task || task.status !== (mode === "initial" ? "created" : "ready")) return false;
      task.status = "running"; task.stage = mode === "initial" ? "analyze" : "write"; task.updatedAt = now;
      return true;
    },
    async updateStage(userId, id, stage, message, now) {
      const task = await this.findById(userId, id);
      if (!task || task.status !== "running") return false;
      task.stage = stage; task.updatedAt = now;
      task.messages.push({ id: task.messages.length + 1, taskId: id, role: "assistant", kind: "process", stage, content: message, createdAt: now });
      return true;
    },
    async fail(userId, id, message, stage, revision, now) {
      const task = await this.findById(userId, id);
      if (task) { task.status = revision ? "ready" : "failed"; task.stage = stage; task.errorMessage = message; task.updatedAt = now; }
    },
    async complete(userId, id, article, review, contentObjectKey, now) {
      const task = await this.findById(userId, id);
      if (!task || task.status !== "running") return false;
      task.status = "ready"; task.stage = "review"; task.updatedAt = now;
      task.title = article.title; task.summary = article.summary; task.content = contentObjectKey ? null : review.content || article.content;
      task.contentObjectKey = contentObjectKey;
      task.categoryId = article.categoryId; task.tagsJson = JSON.stringify(article.tags ?? []); task.review = review.review;
      task.versions.push({ id: task.versions.length + 1, taskId: id, versionNo: task.versions.length + 1,
        title: task.title, summary: task.summary, content: contentObjectKey ? "" : task.content,
        contentObjectKey, changeNote: "generated", createdAt: now });
      return true;
    },
    async publish(userId, id, postId) { const task = await this.findById(userId, id); task.publishedPostId = postId; },
    async appendEvent(taskId, eventType, dataContent, createdAt) {
      const event = { id: nextEvent++, taskId, eventType, dataContent, createdAt };
      events.push(event); return event.id;
    },
    async listEventsAfter(taskId, cursor) { return events.filter((event) => event.taskId === taskId && event.id > cursor); },
    async recoverStale() {},
  };
  const model = {
    async runStage(stage, input) {
      if (options.failStage === stage) throw new Error("simulated model failure");
      const longContent = "## Article\n\n" + "x".repeat(300 * 1024);
      const result = stage === "analyze" ? { angle: "testing" }
        : stage === "research" ? { findings: [] }
          : stage === "article" ? { title: "A durable Worker workflow", summary: "Summary", content: options.large ? longContent : "## Article\n\nFirst draft.", categoryId: "backend", tags: ["Workers"], sources: [] }
            : stage === "revise" ? { title: "A revised Worker workflow", summary: "Revised", content: "## Article\n\nRevised draft.", categoryId: "backend", tags: ["Workers"] }
              : { content: input.content, review: "Reviewed" };
      return { payloadJson: JSON.stringify(result), inputTokens: 10, cachedTokens: 0, outputTokens: 20, model: "mock" };
    },
    async ensureAvailable() {}, async chargeTokens() {},
  };
  const posts = { async create(input) { return { id: 900 + nextId, ...input, status: 1 }; } };
  const publicPosts = { async findPublishedPost(id) { return { id, status: 1 }; } };
  const contentStorage = {
    async write(key, value) { storage.set(key, value); },
    async read(key) { if (!storage.has(key)) throw new Error("missing test object"); return storage.get(key); },
    async delete(key) { storage.delete(key); },
  };
  const service = new BlogAgentTaskService(repository, model, posts, publicPosts, undefined,
    () => Date.parse("2026-10-10T10:00:00.000Z"));
  const env = { INTERNAL_SERVICE_TOKEN: "content-token" };
  const serviceWithStorage = new BlogAgentTaskService(repository, model, posts, publicPosts, contentStorage,
    () => Date.parse("2026-10-10T10:00:00.000Z"));
  return { tasks, events, storage, repository, service, serviceWithStorage, env };
}

function request(url, method = "GET", body, headers = {}) {
  return new Request(`http://content${url}`, {
    method, headers: { ...(body === undefined ? {} : { "content-type": "application/json" }), ...headers },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
}

test("BlogAgent task create is idempotent and details remain owner-scoped", async () => {
  const state = setup();
  const headers = { "x-user-id": "42" };
  const first = await handleBlogAgentTaskRequest(request("/api/blog-agent/tasks", "POST",
    { input: "Explain durable D1 execution", clientRequestId: "request-1" }, headers), state.env, state.service);
  const firstBody = await first.json();
  const retry = await handleBlogAgentTaskRequest(request("/api/blog-agent/tasks", "POST",
    { input: "Different body is ignored on idempotent retry", clientRequestId: "request-1" }, headers), state.env, state.service);
  const retryBody = await retry.json();
  assert.equal(firstBody.data.id, retryBody.data.id);
  assert.equal(state.tasks.length, 1);
  const wrongOwner = await handleBlogAgentTaskRequest(request(`/api/blog-agent/tasks/${firstBody.data.id}`, "GET", undefined,
    { "x-user-id": "7" }), state.env, state.service);
  assert.equal(wrongOwner.status, 404);
});

test("BlogAgent run and revision stream persist stages, versions, and replayable events", async () => {
  const state = setup();
  const created = await state.service.create(42, "Explain durable D1 execution", "blog-task-2");
  const run = await handleBlogAgentTaskRequest(request(`/api/blog-agent/tasks/${created.id}/run/stream`, "POST", undefined,
    { "x-user-id": "42" }), state.env, state.service);
  const streamText = await run.text();
  assert.equal(run.headers.get("content-type"), "text/event-stream; charset=utf-8");
  assert.match(streamText, /event: stage/);
  assert.match(streamText, /event: complete/);
  assert.equal(state.tasks[0].status, "ready");
  assert.equal(state.tasks[0].versions.length, 1);

  const revision = await handleBlogAgentTaskRequest(request(`/api/blog-agent/tasks/${created.id}/messages/stream`, "POST",
    { content: "Make the example concise" }, { "x-user-id": "42" }), state.env, state.service);
  const revisionText = await revision.text();
  assert.match(revisionText, /event: complete/);
  assert.equal(state.tasks[0].versions.length, 2);
  assert.match(state.tasks[0].content, /Revised draft/);

  const replay = await handleBlogAgentTaskRequest(request(`/api/blog-agent/tasks/${created.id}/events?afterEventId=0`, "GET", undefined,
    { "x-user-id": "42" }), state.env, state.service);
  const replayText = await replay.text();
  assert.match(replayText, /event: stage/);
  assert.match(replayText, /event: complete/);
  assert.ok(state.events.length >= 8);
});

test("failed BlogAgent task retains the stage where execution stopped", async () => {
  const state = setup({ failStage: "review" });
  const task = await state.service.create(42, "Explain a durable Worker workflow", "failed-task", "full");
  await assert.rejects(state.service.run(42, task.id), /simulated model failure/);
  assert.equal(state.tasks[0].status, "failed");
  assert.equal(state.tasks[0].stage, "review");
});

test("internal Agent contract is token-protected and returns only ready idempotency results", async () => {
  const state = setup();
  const missingToken = await handleBlogAgentTaskRequest(request("/internal/agent/blogagent/create", "POST",
    { userId: 42, input: "Write a post" }), state.env, state.service);
  assert.equal(missingToken.status, 404);
  const headers = { "x-dinqor-service-token": "content-token" };
  const create = await handleBlogAgentTaskRequest(request("/internal/agent/blogagent/create", "POST",
    { userId: 42, input: "Write a post", clientRequestId: "agent-call-1" }, headers), state.env, state.service);
  const task = await create.json();
  assert.equal(task.status, "created");
  const notReady = await handleBlogAgentTaskRequest(request("/internal/agent/blogagent/find-ready", "POST",
    { userId: 42, clientRequestId: "agent-call-1" }, headers), state.env, state.service);
  assert.equal(await notReady.json(), null);
});

test("BlogAgent stores large task and version bodies through the OSS port and keeps events compact", async () => {
  const state = setup({ large: true });
  const task = await state.serviceWithStorage.create(42, "Write a large article", "large-task");
  const completed = await state.serviceWithStorage.run(42, task.id);
  assert.equal(completed.task.content.length, 300 * 1024 + "## Article\n\n".length);
  assert.equal("contentObjectKey" in completed.task, false);
  assert.equal(state.tasks[0].content, null);
  assert.ok(state.tasks[0].contentObjectKey?.startsWith(`blog-agent/42/tasks/${task.id}/`));
  assert.equal(state.storage.get(state.tasks[0].contentObjectKey), completed.task.content);
  assert.equal(completed.versions[0].content, completed.task.content);
  const completeEvent = state.events.find((event) => event.eventType === "complete");
  assert.ok(completeEvent);
  assert.ok(completeEvent.dataContent.length < 4_000);
});
