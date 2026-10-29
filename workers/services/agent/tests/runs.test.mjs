import assert from "node:assert/strict";
import test, { afterEach, mock } from "node:test";
import { AgentRunService } from "../.test-build/services/agent-run-service.js";
import { AgentMemoryService } from "../.test-build/services/agent-memory-service.js";
import { AgentRecitationService } from "../.test-build/services/agent-recitation-service.js";
import { handleAgentRunRequest } from "../.test-build/controllers/agent-run-controller.js";

afterEach(() => mock.restoreAll());

function createRepository() {
  const state = {
    conversation: { id: 7, userId: 42, status: "ready", runVersion: 0 },
    messages: [],
    events: [],
    nextEventId: 1,
  };
  const appendEvent = async (conversationId, runVersion, eventType, data) => {
    const id = state.nextEventId++;
    state.events.push({ id, conversationId, runVersion, eventType, data });
    return id;
  };
  return {
    state,
    repository: {
      async findOwnedConversation(userId, conversationId) {
        return userId === state.conversation.userId && conversationId === state.conversation.id
          ? { ...state.conversation }
          : null;
      },
      async claimTurn(userId, conversationId, content) {
        if (state.conversation.status === "running") return null;
        state.conversation.status = "running";
        state.conversation.runVersion += 1;
        state.messages.push({ id: state.messages.length + 1, role: "user", content });
        return state.conversation.runVersion;
      },
      async recentMessages() { return [...state.messages]; },
      appendEvent,
      async saveSpecialMessage(_userId, conversationId, runVersion, kind, content) {
        state.messages.push({
          id: state.messages.length + 1,
          role: "assistant",
          kind,
          content: JSON.stringify(content),
          conversationId,
          runVersion,
        });
      },
      async finishTurn(_userId, conversationId, runVersion, answer) {
        state.conversation.status = "ready";
        state.messages.push({ id: state.messages.length + 1, role: "assistant", content: answer });
        return {
          completeEventId: await appendEvent(conversationId, runVersion, "complete", { status: "ready" }),
        };
      },
      async failTurn(_userId, conversationId, runVersion, message) {
        state.conversation.status = "failed";
        return appendEvent(conversationId, runVersion, "error", message);
      },
      async cancelTurn() { return null; },
      async isCancellationRequested() { return false; },
      async listEventsAfter() { return []; },
    },
  };
}

test("Agent executes a streamed text turn and persists replayable terminal events", async () => {
  const { repository, state } = createRepository();
  const charges = [];
  const service = new AgentRunService(repository, {
    async candidates() {
      return {
        managed: true,
        endpoints: [{
          providerId: 4,
          baseUrl: "https://models.example/v1",
          apiKey: "provider-secret",
          model: "test-model",
          apiStyle: "CHAT_COMPLETIONS",
        }],
      };
    },
  }, {
    async ensureAvailable(userId) { assert.equal(userId, 42); return true; },
    async chargeTokens(charge) { charges.push(charge); },
  });
  const chunks = [
    'data: {"choices":[{"delta":{"content":"Hello "}}]}\n\n',
    'data: {"choices":[{"delta":{"content":"world"}}]}\n\n',
    'data: {"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":5,"prompt_tokens_details":{"cached_tokens":3}}}\n\n',
    "data: [DONE]\n\n",
  ];
  mock.method(globalThis, "fetch", async (input, init) => {
    assert.equal(input, "https://models.example/v1/chat/completions");
    assert.equal(new Headers(init.headers).get("authorization"), "Bearer provider-secret");
    const body = JSON.parse(init.body);
    assert.equal(body.model, "test-model");
    assert.deepEqual(body.stream_options, { include_usage: true });
    assert.deepEqual(body.messages.map(({ role }) => role), ["system", "user"]);
    return new Response(new ReadableStream({
      start(controller) {
        const encoder = new TextEncoder();
        for (const chunk of chunks) controller.enqueue(encoder.encode(chunk));
        controller.close();
      },
    }), { headers: { "content-type": "text/event-stream" } });
  });

  const runVersion = await service.claimTurn(42, 7, "Say hello", []);
  const delivered = [];
  const result = await service.executeTurn(42, 7, runVersion, (id, eventType, data) => {
    delivered.push({ id, eventType, data });
  });

  assert.deepEqual(result, { ok: true });
  assert.equal(state.conversation.status, "ready");
  assert.equal(state.messages.at(-1).content, "Hello world");
  assert.deepEqual(delivered.map((event) => event.eventType), [
    "started", "answer_delta", "answer_delta", "complete",
  ]);
  assert.equal(state.events.at(-1).eventType, "complete");
  assert.deepEqual(charges, [{
    userId: 42,
    bizType: "agent-turn",
    bizKey: "conv:7:run:1",
    bizRef: "conv:7",
    inputTokens: 12,
    outputTokens: 5,
    cachedTokens: 3,
    model: "test-model",
    remark: "智能体会话单轮执行",
  }]);
});

test("Agent rejects a turn when Core reports insufficient balance", async () => {
  const { repository, state } = createRepository();
  let chargeCalled = false;
  const service = new AgentRunService(repository, { async candidates() { return { managed: true, endpoints: [] }; } }, {
    async ensureAvailable(userId) { assert.equal(userId, 42); return false; },
    async chargeTokens() { chargeCalled = true; },
  });

  await assert.rejects(service.claimTurn(42, 7, "hello", []), (error) => error.status === 402);
  assert.equal(state.conversation.status, "ready");
  assert.equal(state.messages.length, 0);
  assert.equal(chargeCalled, false);
});

test("Agent charges usage from a Responses API completion event", async () => {
  const { repository } = createRepository();
  const charges = [];
  const service = new AgentRunService(repository, {
    async candidates() {
      return {
        managed: true,
        endpoints: [{
          providerId: 4,
          baseUrl: "https://models.example/v1",
          apiKey: "provider-secret",
          model: "responses-model",
          apiStyle: "RESPONSES",
        }],
      };
    },
  }, {
    async ensureAvailable() { return true; },
    async chargeTokens(charge) { charges.push(charge); },
  });
  mock.method(globalThis, "fetch", async (input, init) => {
    assert.equal(input, "https://models.example/v1/responses");
    assert.equal(JSON.parse(init.body).store, false);
    return new Response(new ReadableStream({
      start(controller) {
        const encoder = new TextEncoder();
        controller.enqueue(encoder.encode('data: {"type":"response.output_text.delta","delta":"Done"}\n\n'));
        controller.enqueue(encoder.encode('data: {"type":"response.completed","response":{"usage":{"input_tokens":8,"output_tokens":2,"input_tokens_details":{"cached_tokens":4}}}}\n\n'));
        controller.close();
      },
    }), { headers: { "content-type": "text/event-stream" } });
  });

  const runVersion = await service.claimTurn(42, 7, "Say done", []);
  const result = await service.executeTurn(42, 7, runVersion, () => {});

  assert.deepEqual(result, { ok: true });
  assert.deepEqual(charges, [{
    userId: 42,
    bizType: "agent-turn",
    bizKey: "conv:7:run:1",
    bizRef: "conv:7",
    inputTokens: 8,
    outputTokens: 2,
    cachedTokens: 4,
    model: "responses-model",
    remark: "智能体会话单轮执行",
  }]);
});

test("delegate_subtask runs only bounded read-only child calls and bills every model subcall", async () => {
  const { repository, state } = createRepository();
  const charges = [];
  const contentCalls = [];
  const service = new AgentRunService(repository, {
    async candidates() {
      return { managed: true, endpoints: [{ providerId: 4, baseUrl: "https://models.example/v1",
        apiKey: "provider-secret", model: "subtask-model", apiStyle: "CHAT_COMPLETIONS" }] };
    },
  }, {
    async ensureAvailable() { return true; },
    async chargeTokens(charge) { charges.push(charge); },
  }, undefined, undefined, undefined, undefined, undefined, undefined, undefined, undefined, undefined, {
    async execute(userId, name, args) {
      contentCalls.push({ userId, name, args });
      return { data: { posts: [{ id: 55, title: "Owned evidence" }] }, answer: "Owned article 55 matched." };
    },
  });

  let modelCall = 0;
  const stream = (events) => new Response(new ReadableStream({
    start(controller) {
      const encoder = new TextEncoder();
      for (const event of events) controller.enqueue(encoder.encode(`data: ${JSON.stringify(event)}\n\n`));
      controller.enqueue(encoder.encode("data: [DONE]\n\n"));
      controller.close();
    },
  }), { headers: { "content-type": "text/event-stream" } });
  const toolEvent = (index, name, args) => ({
    choices: [{ delta: { tool_calls: [{ index, function: { name, arguments: JSON.stringify(args) } }] } }],
  });
  const usage = { choices: [], usage: { prompt_tokens: 10, completion_tokens: 2 } };
  mock.method(globalThis, "fetch", async (_input, init) => {
    const body = JSON.parse(init.body);
    modelCall++;
    if (modelCall === 1) {
      assert.ok(body.tools.some((tool) => tool.function.name === "delegate_subtask"));
      return stream([toolEvent(0, "delegate_subtask", {
        objective: "Find related posts", brief: "Look for evidence about migration", tools: ["query_posts"],
      }), usage]);
    }
    if (modelCall === 2) {
      const toolNames = body.tools.map((tool) => tool.function.name);
      assert.ok(toolNames.includes("query_posts"));
      assert.ok(!toolNames.includes("delegate_subtask"));
      assert.ok(!toolNames.includes("schedule_plan"));
      return stream([
        toolEvent(0, "query_posts", { search: "migration" }),
        toolEvent(1, "schedule_plan", { topic: "must never execute" }),
        usage,
      ]);
    }
    if (modelCall === 3) {
      const lastMessage = body.messages.at(-1).content;
      assert.match(lastMessage, /Owned article 55 matched/);
      assert.match(lastMessage, /未授权的只读工具 schedule_plan/);
      return stream([
        { choices: [{ delta: { content: "The evidence includes one owned article." } }] },
        usage,
      ]);
    }
    assert.equal(modelCall, 4);
    assert.equal(body.tools, undefined, "the parent synthesis cannot execute a second mutation");
    assert.match(body.messages.at(-1).content, /The evidence includes one owned article/);
    return stream([
      { choices: [{ delta: { content: "The owned article is relevant to the migration." } }] },
      usage,
    ]);
  });

  const runVersion = await service.claimTurn(42, 7, "Find relevant migration posts", []);
  const delivered = [];
  const result = await service.executeTurn(42, 7, runVersion, (_id, eventType, data) => {
    delivered.push({ eventType, data });
  });

  assert.deepEqual(result, { ok: true });
  assert.equal(state.messages.at(-1).content, "The owned article is relevant to the migration.");
  assert.deepEqual(contentCalls, [{ userId: 42, name: "query_posts", args: { search: "migration" } }]);
  assert.equal(delivered.some((event) => event.eventType === "tool_result" && event.data.tool === "delegate_subtask"), true);
  assert.deepEqual(charges.map(({ bizKey }) => bizKey), [
    "conv:7:run:1:subtask:1", "conv:7:run:1:subtask:2", "conv:7:run:1:delegate-synthesis", "conv:7:run:1",
  ]);
  assert.equal(charges.length, 4);
  assert.ok(charges.every((charge) => charge.inputTokens === 10 && charge.outputTokens === 2));
});

test("Agent saves plan_tasks through the run service and emits a replayable plan event", async () => {
  const { repository, state } = createRepository();
  const saved = [];
  const plans = {
    async replace(userId, conversationId, runVersion, value) {
      assert.deepEqual([userId, conversationId, runVersion], [42, 7, 1]);
      saved.push(value);
      const items = value.items;
      const eventId = await repository.appendEvent(conversationId, runVersion, "plan", items);
      return { eventId, result: { ok: true, total: items.length, unfinished: 1, items, hint: "continue" } };
    },
  };
  const service = new AgentRunService(repository, {
    async candidates() {
      return { managed: true, endpoints: [{ providerId: 4, baseUrl: "https://models.example/v1",
        apiKey: "provider-secret", model: "plan-model", apiStyle: "CHAT_COMPLETIONS" }] };
    },
  }, { async ensureAvailable() { return true; }, async chargeTokens() {} },
  undefined, undefined, undefined, undefined, undefined, undefined, plans);
  const plan = { items: [{ title: "Finish migration", status: "IN_PROGRESS" }] };
  mock.method(globalThis, "fetch", async (_input, init) => {
    const body = JSON.parse(init.body);
    const definition = body.tools.find((tool) => tool.function.name === "plan_tasks");
    assert.ok(definition);
    assert.deepEqual(definition.function.parameters.required, ["items"]);
    const events = [
      { choices: [{ delta: { tool_calls: [{ index: 0, function: {
        name: "plan_tasks", arguments: JSON.stringify(plan),
      } }] } }] },
      { choices: [], usage: { prompt_tokens: 9, completion_tokens: 3 } },
    ];
    return new Response(new ReadableStream({
      start(controller) {
        const encoder = new TextEncoder();
        for (const event of events) controller.enqueue(encoder.encode(`data: ${JSON.stringify(event)}\n\n`));
        controller.enqueue(encoder.encode("data: [DONE]\n\n"));
        controller.close();
      },
    }), { headers: { "content-type": "text/event-stream" } });
  });
  const runVersion = await service.claimTurn(42, 7, "Finish a multistep migration", []);
  const delivered = [];
  const result = await service.executeTurn(42, 7, runVersion, (id, eventType, data) => delivered.push({ id, eventType, data }));
  assert.deepEqual(result, { ok: true });
  assert.deepEqual(saved, [plan]);
  assert.deepEqual(delivered.map((event) => event.eventType), ["started", "plan", "tool_result", "complete"]);
  assert.deepEqual(state.events.find((event) => event.eventType === "plan").data, plan.items);
});

test("Agent image_generate stores an image and streams the Content media-library result", async () => {
  const { repository, state } = createRepository();
  let generated;
  const imageService = {
    async generate(...args) {
      generated = args;
      return {
        ok: true, assetId: 99, url: "https://cdn.example/99.png", title: "D1 illustration",
        width: 1, height: 1, contentType: "image/png",
      };
    },
  };
  const charges = [];
  const service = new AgentRunService(repository, {
    async candidates() {
      return { managed: true, endpoints: [{ providerId: 4, baseUrl: "https://models.example/v1",
        apiKey: "provider-secret", model: "agent-model", apiStyle: "CHAT_COMPLETIONS" }] };
    },
  }, { async ensureAvailable() { return true; }, async chargeTokens(value) { charges.push(value); } },
  undefined, undefined, undefined, undefined, undefined, undefined, undefined, undefined, imageService);
  mock.method(globalThis, "fetch", async (_input, init) => {
    const body = JSON.parse(init.body);
    const tool = body.tools.find((candidate) => candidate.function.name === "image_generate");
    assert.deepEqual(tool.function.parameters.required, ["prompt"]);
    const events = [
      { choices: [{ delta: { tool_calls: [{ index: 0, function: {
        name: "image_generate", arguments: JSON.stringify({ prompt: "small database diagram", title: "D1 illustration" }),
      } }] } }] },
      { choices: [], usage: { prompt_tokens: 20, completion_tokens: 3 } },
    ];
    return new Response(new ReadableStream({
      start(controller) {
        const encoder = new TextEncoder();
        for (const event of events) controller.enqueue(encoder.encode(`data: ${JSON.stringify(event)}\n\n`));
        controller.enqueue(encoder.encode("data: [DONE]\n\n"));
        controller.close();
      },
    }), { headers: { "content-type": "text/event-stream" } });
  });

  const runVersion = await service.claimTurn(42, 7, "生成一张 D1 图", []);
  const delivered = [];
  const result = await service.executeTurn(42, 7, runVersion, (id, eventType, data) => delivered.push({ id, eventType, data }));
  assert.deepEqual(result, { ok: true });
  assert.deepEqual(generated, [42, 7, 1, { prompt: "small database diagram", title: "D1 illustration" }]);
  assert.equal(state.messages.at(-1).content, "![D1 illustration](https://cdn.example/99.png)");
  assert.deepEqual(delivered.map((event) => event.eventType), ["started", "tool_result", "complete"]);
  assert.equal(charges[0].bizType, "agent-turn");
});

test("Agent query_posts calls the owner-scoped Content contract and returns a readable result", async () => {
  const { repository, state } = createRepository();
  let query;
  const contentTools = {
    async execute(userId, toolName, args) {
      query = [userId, toolName, args];
      return { data: { ok: true, total: 1, posts: [{ id: 12, title: "D1 migration", status: "draft" }] },
        answer: "找到一篇草稿：D1 migration。" };
    },
  };
  const service = new AgentRunService(repository, {
    async candidates() {
      return { managed: true, endpoints: [{ providerId: 4, baseUrl: "https://models.example/v1",
        apiKey: "provider-secret", model: "agent-model", apiStyle: "CHAT_COMPLETIONS" }] };
    },
  }, { async ensureAvailable() { return true; }, async chargeTokens() {} },
  undefined, undefined, undefined, undefined, undefined, undefined, undefined, undefined, undefined, contentTools);
  mock.method(globalThis, "fetch", async (_input, init) => {
    const body = JSON.parse(init.body);
    assert.ok(body.tools.some((candidate) => candidate.function.name === "query_posts"));
    const events = [
      { choices: [{ delta: { tool_calls: [{ index: 0, function: {
        name: "query_posts", arguments: JSON.stringify({ status: "draft", limit: 10 }),
      } }] } }] },
      { choices: [], usage: { prompt_tokens: 16, completion_tokens: 2 } },
    ];
    return new Response(new ReadableStream({
      start(controller) {
        const encoder = new TextEncoder();
        for (const event of events) controller.enqueue(encoder.encode(`data: ${JSON.stringify(event)}\n\n`));
        controller.enqueue(encoder.encode("data: [DONE]\n\n"));
        controller.close();
      },
    }), { headers: { "content-type": "text/event-stream" } });
  });
  const runVersion = await service.claimTurn(42, 7, "我写过什么草稿？", []);
  const delivered = [];
  const result = await service.executeTurn(42, 7, runVersion, (id, eventType, data) => delivered.push({ id, eventType, data }));
  assert.deepEqual(result, { ok: true });
  assert.deepEqual(query, [42, "query_posts", { status: "draft", limit: 10 }]);
  assert.equal(state.messages.at(-1).content, "找到一篇草稿：D1 migration。");
  assert.deepEqual(delivered.map((event) => event.eventType), ["started", "tool_result", "complete"]);
});

test("Agent persists schedule_plan as a user approval before Content receives a write", async () => {
  const { repository, state } = createRepository();
  const charges = [];
  const approvalCalls = [];
  const approvals = { async requestContentMutation(...args) {
    approvalCalls.push(args);
    state.conversation.status = "awaiting_approval";
    const eventId = await repository.appendEvent(args[1], args[2], "approval_required", { tool: args[3] });
    return { eventId, approval: { id: 9, status: "PENDING", toolName: args[3] } };
  } };
  const contentTools = { async execute() { throw new Error("must wait for approval"); } };
  const service = new AgentRunService(repository, {
    async candidates() {
      return { managed: true, endpoints: [{ providerId: 4, baseUrl: "https://models.example/v1",
        apiKey: "provider-secret", model: "schedule-model", apiStyle: "CHAT_COMPLETIONS" }] };
    },
  }, { async ensureAvailable() { return true; }, async chargeTokens(value) { charges.push(value); } },
  approvals, undefined, undefined, undefined, undefined, undefined, undefined, undefined, undefined, contentTools);
  const args = { topic: "D1 的正确用法", triggerTime: "09:30" };
  mock.method(globalThis, "fetch", async (_input, init) => {
    const body = JSON.parse(init.body);
    assert.ok(body.tools.some((tool) => tool.function.name === "schedule_plan"));
    const events = [
      { choices: [{ delta: { tool_calls: [{ index: 0, function: {
        name: "schedule_plan", arguments: JSON.stringify(args),
      } }] } }] },
      { choices: [], usage: { prompt_tokens: 9, completion_tokens: 3 } },
    ];
    return new Response(new ReadableStream({
      start(controller) {
        const encoder = new TextEncoder();
        for (const event of events) controller.enqueue(encoder.encode(`data: ${JSON.stringify(event)}\n\n`));
        controller.enqueue(encoder.encode("data: [DONE]\n\n"));
        controller.close();
      },
    }), { headers: { "content-type": "text/event-stream" } });
  });
  const runVersion = await service.claimTurn(42, 7, "每天自动写博客", []);
  const delivered = [];
  const result = await service.executeTurn(42, 7, runVersion, (id, eventType, data) => delivered.push({ id, eventType, data }));

  assert.deepEqual(result, { ok: true, awaitingApproval: true });
  assert.deepEqual(approvalCalls.map((call) => call.slice(0, 4)), [[42, 7, 1, "schedule_plan"]]);
  assert.equal(charges.length, 1);
  assert.equal(state.conversation.status, "awaiting_approval");
  assert.ok(delivered.some((event) => event.eventType === "approval_required"));
});

test("Agent image_resend fetches a library image through the owner-scoped Content mapper", async () => {
  const { repository, state } = createRepository();
  const lookups = [];
  const imageService = {
    async findOwned(...args) {
      lookups.push(args);
      return { assetId: 44, url: "https://cdn.example/44.png", title: "Diagram", contentType: "image/png" };
    },
  };
  const service = new AgentRunService(repository, {
    async candidates() {
      return { managed: true, endpoints: [{ providerId: 4, baseUrl: "https://models.example/v1",
        apiKey: "provider-secret", model: "agent-model", apiStyle: "CHAT_COMPLETIONS" }] };
    },
  }, { async ensureAvailable() { return true; }, async chargeTokens() {} },
  undefined, undefined, undefined, undefined, undefined, undefined, undefined, undefined, imageService);
  mock.method(globalThis, "fetch", async (_input, init) => {
    const body = JSON.parse(init.body);
    assert.ok(body.tools.some((candidate) => candidate.function.name === "image_resend"));
    const events = [
      { choices: [{ delta: { tool_calls: [{ index: 0, function: {
        name: "image_resend", arguments: JSON.stringify({ source: "asset_id", assetId: 44 }),
      } }] } }] },
      { choices: [], usage: { prompt_tokens: 10, completion_tokens: 2 } },
    ];
    return new Response(new ReadableStream({
      start(controller) {
        const encoder = new TextEncoder();
        for (const event of events) controller.enqueue(encoder.encode(`data: ${JSON.stringify(event)}\n\n`));
        controller.enqueue(encoder.encode("data: [DONE]\n\n"));
        controller.close();
      },
    }), { headers: { "content-type": "text/event-stream" } });
  });
  const runVersion = await service.claimTurn(42, 7, "把素材 44 再发一次", []);
  const result = await service.executeTurn(42, 7, runVersion, () => {});
  assert.deepEqual(result, { ok: true });
  assert.deepEqual(lookups, [[42, 44]]);
  assert.equal(state.messages.at(-1).content, "![Diagram](https://cdn.example/44.png)");
});

test("Agent persists remember results and injects matching owner memory into the next turn", async () => {
  const { repository, state } = createRepository();
  const memories = new Map();
  let nextMemoryId = 1;
  const memoryService = new AgentMemoryService({
    async upsert(input) {
      const key = `${input.userId}:${input.memoryKey}`;
      const current = memories.get(key);
      const row = current ?? {
        id: nextMemoryId++, ...input, hitCount: 0, lastUsedAt: null,
        createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(),
      };
      const updated = { ...row, kind: input.kind, content: input.content, updatedAt: new Date().toISOString() };
      memories.set(key, updated);
      return updated;
    },
    async delete(userId, memoryKey) { return memories.delete(`${userId}:${memoryKey}`); },
    async list(userId) { return [...memories.values()].filter((row) => row.userId === userId); },
    async markRecalled() {},
  });
  const service = new AgentRunService(repository, {
    async candidates() {
      return {
        managed: true,
        endpoints: [{ providerId: 4, baseUrl: "https://models.example/v1", apiKey: "provider-secret",
          model: "memory-model", apiStyle: "CHAT_COMPLETIONS" }],
      };
    },
  }, {
    async ensureAvailable() { return true; },
    async chargeTokens() {},
  }, undefined, undefined, memoryService);
  let callNumber = 0;
  mock.method(globalThis, "fetch", async (input, init) => {
    assert.equal(input, "https://models.example/v1/chat/completions");
    const body = JSON.parse(init.body);
    assert.deepEqual(body.tools.map((tool) => tool.function.name), [
      "remember", "recall_memory", "emit_quiz", "emit_artifact", "plan_tasks", "image_generate",
      "image_resend", "query_posts", "query_plans", "query_plan_series", "query_email_reminders", "query_unsynced_posts",
      "create_plan_series", "update_plan_series", "activate_plan_series", "retry_plan_series",
      "schedule_plan", "update_plan", "email_reminder", "update_email_reminder", "delegate_subtask",
    ]);
    callNumber += 1;
    if (callNumber === 1) {
      const events = [
        { choices: [{ delta: { tool_calls: [{ index: 0, function: {
          name: "remember", arguments: JSON.stringify({
            memoryKey: "writing-style", kind: "PREFERENCE", content: "偏好简洁中文",
          }),
        } }] } }] },
        { choices: [], usage: { prompt_tokens: 10, completion_tokens: 4 } },
      ];
      return new Response(new ReadableStream({
        start(controller) {
          const encoder = new TextEncoder();
          for (const event of events) controller.enqueue(encoder.encode(`data: ${JSON.stringify(event)}\n\n`));
          controller.enqueue(encoder.encode("data: [DONE]\n\n"));
          controller.close();
        },
      }), { headers: { "content-type": "text/event-stream" } });
    }
    assert.match(body.messages[0].content, /writing-style.*偏好简洁中文/);
    return new Response(new ReadableStream({
      start(controller) {
        const encoder = new TextEncoder();
        controller.enqueue(encoder.encode('data: {"choices":[{"delta":{"content":"你偏好简洁中文。"}}]}\n\n'));
        controller.enqueue(encoder.encode('data: {"choices":[],"usage":{"prompt_tokens":14,"completion_tokens":5}}\n\n'));
        controller.enqueue(encoder.encode("data: [DONE]\n\n"));
        controller.close();
      },
    }), { headers: { "content-type": "text/event-stream" } });
  });

  let runVersion = await service.claimTurn(42, 7, "记住我的写作偏好", []);
  const firstEvents = [];
  await service.executeTurn(42, 7, runVersion, (_id, eventType) => firstEvents.push(eventType));
  assert.deepEqual(firstEvents, ["started", "tool_result", "complete"], JSON.stringify(state.events));
  assert.match(state.messages.at(-1).content, /已记住/);

  runVersion = await service.claimTurn(42, 7, "我偏好怎样的回复？", []);
  const second = await service.executeTurn(42, 7, runVersion, () => {});
  assert.deepEqual(second, { ok: true });
  assert.equal(state.messages.at(-1).content, "你偏好简洁中文。");
  assert.equal(callNumber, 2);
});

test("Agent knowledge tools use the verified owner and return Core-backed query results", async () => {
  const { repository, state } = createRepository();
  const charges = [];
  const calls = [];
  const recitation = new AgentRecitationService({
    async searchMaterials(userId, filter) {
      calls.push(["search", userId, filter]);
      return [{
        id: 31, title: "JWT", content: "JWT 包含头部、载荷和签名。", knowledgeType: "CONCEPT",
        testMode: "CONCEPT", masteryScore: 60, masteryLevel: "RECALLING", reviewCount: 2,
        nextReviewAt: "2026-10-09T00:00:00.000Z",
      }];
    },
    async countMaterials(userId, filter) {
      calls.push(["count", userId, filter]);
      return filter.masteryLevel === "MASTERED" ? 1 : filter.dueOnly ? 2 : filter.search ? 1 : 4;
    },
    async createMaterial() { throw new Error("unexpected write"); },
  });
  const service = new AgentRunService(repository, {
    async candidates() {
      return { managed: true, endpoints: [{ providerId: 1, baseUrl: "https://models.example/v1",
        apiKey: "key", model: "tool-model", apiStyle: "CHAT_COMPLETIONS" }] };
    },
  }, {
    async ensureAvailable() { return true; },
    async chargeTokens(value) { charges.push(value); },
  }, undefined, undefined, undefined, undefined, recitation);
  mock.method(globalThis, "fetch", async (_input, init) => {
    const body = JSON.parse(init.body);
    assert.ok(body.tools.some((tool) => tool.function.name === "query_knowledge"));
    const events = [
      { choices: [{ delta: { tool_calls: [{ index: 0, function: {
        name: "query_knowledge", arguments: JSON.stringify({ search: "JWT", limit: 5 }),
      } }] } }] },
      { choices: [], usage: { prompt_tokens: 18, completion_tokens: 7 } },
    ];
    return new Response(new ReadableStream({
      start(controller) {
        const encoder = new TextEncoder();
        for (const event of events) controller.enqueue(encoder.encode(`data: ${JSON.stringify(event)}\n\n`));
        controller.enqueue(encoder.encode("data: [DONE]\n\n"));
        controller.close();
      },
    }), { headers: { "content-type": "text/event-stream" } });
  });

  const runVersion = await service.claimTurn(42, 7, "我保存的 JWT 内容是什么？", []);
  const result = await service.executeTurn(42, 7, runVersion, () => {});

  assert.deepEqual(result, { ok: true });
  assert.match(state.messages.at(-1).content, /JWT 包含头部、载荷和签名/);
  assert.equal(calls[0][1], 42);
  assert.deepEqual(calls[0][2], { search: "JWT", masteryLevel: "", dueOnly: false, limit: 5 });
  assert.equal(calls.filter((call) => call[0] === "count").length, 4);
  assert.equal(state.events.find((event) => event.eventType === "tool_result").data.tool, "query_knowledge");
  assert.deepEqual(charges.map((charge) => charge.bizType), ["agent-turn"]);
});

test("Agent create_knowledge writes only when the model asks with the authenticated owner", async () => {
  const { repository, state } = createRepository();
  const created = [];
  const recitation = new AgentRecitationService({
    async searchMaterials() { return []; },
    async countMaterials() { return 0; },
    async createMaterial(userId, draft) {
      created.push({ userId, draft });
      return { id: 53, ...draft, testMode: "CONCEPT", masteryScore: 0, masteryLevel: "NEW",
        reviewCount: 0, nextReviewAt: null };
    },
  });
  const service = new AgentRunService(repository, {
    async candidates() {
      return { managed: true, endpoints: [{ providerId: 1, baseUrl: "https://models.example/v1",
        apiKey: "key", model: "tool-model", apiStyle: "CHAT_COMPLETIONS" }] };
    },
  }, { async ensureAvailable() { return true; }, async chargeTokens() {} },
  undefined, undefined, undefined, undefined, recitation);
  mock.method(globalThis, "fetch", async (_input, init) => {
    const body = JSON.parse(init.body);
    assert.ok(body.tools.some((tool) => tool.function.name === "create_knowledge"));
    const event = { choices: [{ delta: { tool_calls: [{ index: 0, function: {
      name: "create_knowledge", arguments: JSON.stringify({
        title: "复习提纲", content: "JWT 有头部、载荷和签名。", knowledgeType: "CONCEPT",
      }),
    } }] } }] };
    return new Response(new ReadableStream({
      start(controller) {
        const encoder = new TextEncoder();
        controller.enqueue(encoder.encode(`data: ${JSON.stringify(event)}\n\n`));
        controller.enqueue(encoder.encode('data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5}}\n\n'));
        controller.enqueue(encoder.encode("data: [DONE]\n\n"));
        controller.close();
      },
    }), { headers: { "content-type": "text/event-stream" } });
  });

  const runVersion = await service.claimTurn(42, 7, "把这段加入知识库", []);
  await service.executeTurn(42, 7, runVersion, () => {});

  assert.deepEqual(created, [{ userId: 42, draft: {
    title: "复习提纲", content: "JWT 有头部、载荷和签名。", knowledgeType: "CONCEPT",
  } }]);
  assert.match(state.messages.at(-1).content, /已将“复习提纲”加入/);
});

test("Agent starts a Core-owned quiz and asks the model to generate questions from its material", async () => {
  const { repository, state } = createRepository();
  const calls = [];
  const charges = [];
  const recitation = new AgentRecitationService({
    async searchMaterials() { return []; },
    async countMaterials() { return 0; },
    async createMaterial() { throw new Error("unexpected write"); },
    async openQuiz(userId, materialId, conversationId) {
      calls.push({ userId, materialId, conversationId });
      return {
        quizSessionId: 91, materialId, title: "HTTP basics", knowledgeType: "CONCEPT",
        testMode: "CONCEPT", content: "Requests carry a method and headers.", contentTruncated: false,
      };
    },
    async recordQuiz() { throw new Error("unexpected grading"); },
  });
  const service = new AgentRunService(repository, {
    async candidates() {
      return { managed: true, endpoints: [{ providerId: 1, baseUrl: "https://models.example/v1",
        apiKey: "key", model: "tool-model", apiStyle: "CHAT_COMPLETIONS" }] };
    },
  }, {
    async ensureAvailable() { return true; },
    async chargeTokens(value) { charges.push(value); },
  }, undefined, undefined, undefined, undefined, recitation);
  let callNumber = 0;
  mock.method(globalThis, "fetch", async (_input, init) => {
    const body = JSON.parse(init.body);
    callNumber += 1;
    if (callNumber === 1) {
      assert.ok(body.tools.some((tool) => tool.function.name === "start_knowledge_quiz"));
      return new Response(new ReadableStream({
        start(controller) {
          const encoder = new TextEncoder();
          controller.enqueue(encoder.encode(`data: ${JSON.stringify({ choices: [{ delta: { tool_calls: [{ index: 0, function: {
            name: "start_knowledge_quiz", arguments: JSON.stringify({ materialId: 31, questionCount: 3 }),
          } }] } }] })}\n\n`));
          controller.enqueue(encoder.encode('data: {"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":3}}\n\n'));
          controller.enqueue(encoder.encode("data: [DONE]\n\n"));
          controller.close();
        },
      }), { headers: { "content-type": "text/event-stream" } });
    }
    assert.equal(body.tools, undefined);
    assert.match(body.messages.at(-1).content, /Requests carry a method and headers/);
    assert.match(body.messages.at(-1).content, /3 道/);
    return new Response(new ReadableStream({
      start(controller) {
        const encoder = new TextEncoder();
        controller.enqueue(encoder.encode('data: {"choices":[{"delta":{"content":"1. 请求使用什么方法？\\n2. 请求头用于传递什么？\\n3. 请求由哪些部分组成？"}}]}\n\n'));
        controller.enqueue(encoder.encode('data: {"choices":[],"usage":{"prompt_tokens":30,"completion_tokens":18}}\n\n'));
        controller.enqueue(encoder.encode("data: [DONE]\n\n"));
        controller.close();
      },
    }), { headers: { "content-type": "text/event-stream" } });
  });

  const runVersion = await service.claimTurn(42, 7, "测验一下我的 HTTP 知识", []);
  await service.executeTurn(42, 7, runVersion, () => {});

  assert.deepEqual(calls, [{ userId: 42, materialId: 31, conversationId: 7 }]);
  assert.match(state.messages.at(-1).content, /1\. 请求使用什么方法/);
  assert.equal(state.events.find((event) => event.eventType === "tool_result").data.result.quizSessionId, 91);
  assert.deepEqual(charges.map((charge) => charge.bizKey), [
    "conv:7:run:1:quiz-start", "conv:7:run:1:quiz-generation",
  ]);
});

test("Agent records quiz evidence against the current conversation owner", async () => {
  const { repository, state } = createRepository();
  const calls = [];
  const recitation = new AgentRecitationService({
    async searchMaterials() { return []; },
    async countMaterials() { return 0; },
    async createMaterial() { throw new Error("unexpected write"); },
    async openQuiz() { throw new Error("unexpected quiz"); },
    async recordQuiz(userId, conversationId, input) {
      calls.push({ userId, conversationId, input });
      return {
        attemptId: 92, materialId: 31, title: "HTTP basics", score: 75, questionCount: 1,
        correctCount: 0, masteryLevel: "APPLYING", previousMasteryLevel: "FAMILIAR",
        reviewCount: 2, nextReviewAt: "2026-10-17T00:00:00.000Z",
      };
    },
  });
  const service = new AgentRunService(repository, {
    async candidates() {
      return { managed: true, endpoints: [{ providerId: 1, baseUrl: "https://models.example/v1",
        apiKey: "key", model: "tool-model", apiStyle: "CHAT_COMPLETIONS" }] };
    },
  }, { async ensureAvailable() { return true; }, async chargeTokens() {} },
  undefined, undefined, undefined, undefined, recitation);
  mock.method(globalThis, "fetch", async (_input, init) => {
    const body = JSON.parse(init.body);
    assert.ok(body.tools.some((tool) => tool.function.name === "record_knowledge_quiz"));
    return new Response(new ReadableStream({
      start(controller) {
        const encoder = new TextEncoder();
        controller.enqueue(encoder.encode(`data: ${JSON.stringify({ choices: [{ delta: { tool_calls: [{ index: 0, function: {
          name: "record_knowledge_quiz", arguments: JSON.stringify({
            quizSessionId: 91, verdict: "部分正确", items: [{
              question: "请求使用什么方法？", userAnswer: "GET", comment: "答对了请求方法。", credit: 0.75,
            }],
          }),
        } }] } }] })}\n\n`));
        controller.enqueue(encoder.encode('data: {"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":3}}\n\n'));
        controller.enqueue(encoder.encode("data: [DONE]\n\n"));
        controller.close();
      },
    }), { headers: { "content-type": "text/event-stream" } });
  });

  const runVersion = await service.claimTurn(42, 7, "我答 GET", []);
  await service.executeTurn(42, 7, runVersion, () => {});

  assert.deepEqual(calls, [{ userId: 42, conversationId: 7, input: {
    quizSessionId: 91, verdict: "部分正确", items: [{
      question: "请求使用什么方法？", userAnswer: "GET", comment: "答对了请求方法。", credit: 0.75,
    }],
  } }]);
  assert.match(state.messages.at(-1).content, /得分 75 分/);
});

test("Agent emits and persists a normalized interactive quiz card", async () => {
  const { repository, state } = createRepository();
  const charges = [];
  const service = new AgentRunService(repository, {
    async candidates() {
      return { managed: true, endpoints: [{ providerId: 1, baseUrl: "https://models.example/v1",
        apiKey: "key", model: "tool-model", apiStyle: "CHAT_COMPLETIONS" }] };
    },
  }, { async ensureAvailable() { return true; }, async chargeTokens(value) { charges.push(value); } });
  const cardInput = {
    quizSessionId: 91,
    title: "  HTTP basics  ",
    questions: [
      { prompt: "  What method retrieves a resource?  ", type: "single", options: ["GET", "POST"] },
      { prompt: "Explain a response header", type: "text", options: ["discard me"] },
      { prompt: "One option should become text", type: "multiple", options: ["only"] },
    ],
  };
  mock.method(globalThis, "fetch", async (_input, init) => {
    const body = JSON.parse(init.body);
    const definition = body.tools.find((tool) => tool.function.name === "emit_quiz").function;
    assert.deepEqual(definition.parameters.required, ["questions"]);
    return new Response(new ReadableStream({
      start(controller) {
        const encoder = new TextEncoder();
        controller.enqueue(encoder.encode(`data: ${JSON.stringify({ choices: [{ delta: { tool_calls: [{ index: 0, function: {
          name: "emit_quiz", arguments: JSON.stringify(cardInput),
        } }] } }] })}\n\n`));
        controller.enqueue(encoder.encode('data: {"choices":[],"usage":{"prompt_tokens":8,"completion_tokens":4}}\n\n'));
        controller.enqueue(encoder.encode("data: [DONE]\n\n"));
        controller.close();
      },
    }), { headers: { "content-type": "text/event-stream" } });
  });

  const runVersion = await service.claimTurn(42, 7, "Make me an HTTP quiz", []);
  const delivered = [];
  const result = await service.executeTurn(42, 7, runVersion, (id, eventType, data) => {
    delivered.push({ id, eventType, data });
  });

  const quizMessage = state.messages.find((message) => message.kind === "quiz");
  const quiz = JSON.parse(quizMessage.content);
  assert.deepEqual(result, { ok: true });
  assert.deepEqual(quiz, {
    type: "quiz",
    id: "91",
    title: "HTTP basics",
    questions: [
      { id: "q1", prompt: "What method retrieves a resource?", type: "single", options: ["GET", "POST"] },
      { id: "q2", prompt: "Explain a response header", type: "text", options: [] },
      { id: "q3", prompt: "One option should become text", type: "text", options: [] },
    ],
  });
  assert.equal(state.messages.at(-1).content, "答题卡已发出，请在卡片中作答。");
  assert.deepEqual(delivered.map((event) => event.eventType), [
    "started", "tool_result", "quiz", "complete",
  ]);
  assert.deepEqual(delivered.find((event) => event.eventType === "quiz").data, quiz);
  assert.equal(charges.length, 1);
  assert.equal(charges[0].bizKey, "conv:7:run:1");
});

test("Agent emits a bounded multi-file artifact card with a safe browser entry", async () => {
  const { repository, state } = createRepository();
  const service = new AgentRunService(repository, {
    async candidates() {
      return { managed: true, endpoints: [{ providerId: 1, baseUrl: "https://models.example/v1",
        apiKey: "key", model: "tool-model", apiStyle: "CHAT_COMPLETIONS" }] };
    },
  }, { async ensureAvailable() { return true; }, async chargeTokens() {} });
  const artifactInput = {
    name: "  Small site  ", framework: "  static ", runHint: "  Open index.html  ",
    files: [
      { path: " /src//main.ts ", content: "x".repeat(20_004) },
      { path: "src/ui.css", content: "c".repeat(20_000) },
      { path: "public/index.html", content: "<!doctype html>" + "h".repeat(5_000) },
    ],
  };
  mock.method(globalThis, "fetch", async (_input, init) => {
    const body = JSON.parse(init.body);
    assert.ok(body.tools.some((tool) => tool.function.name === "emit_artifact"));
    return new Response(new ReadableStream({
      start(controller) {
        const encoder = new TextEncoder();
        controller.enqueue(encoder.encode(`data: ${JSON.stringify({ choices: [{ delta: { tool_calls: [{ index: 0, function: {
          name: "emit_artifact", arguments: JSON.stringify(artifactInput),
        } }] } }] })}\n\n`));
        controller.enqueue(encoder.encode('data: {"choices":[],"usage":{"prompt_tokens":9,"completion_tokens":6}}\n\n'));
        controller.enqueue(encoder.encode("data: [DONE]\n\n"));
        controller.close();
      },
    }), { headers: { "content-type": "text/event-stream" } });
  });

  const runVersion = await service.claimTurn(42, 7, "Build a small site", []);
  const delivered = [];
  await service.executeTurn(42, 7, runVersion, (id, eventType, data) => {
    delivered.push({ id, eventType, data });
  });

  const artifactMessage = state.messages.find((message) => message.kind === "artifact");
  const artifact = JSON.parse(artifactMessage.content);
  assert.deepEqual(artifact, {
    type: "artifact",
    id: "artifact-7-1",
    name: "Small site",
    framework: "static",
    runHint: "Open index.html",
    entry: "public/index.html",
    files: [
      { path: "src/main.ts", language: "typescript", content: "x".repeat(20_000), truncated: true },
      { path: "src/ui.css", language: "css", content: "c".repeat(20_000), truncated: false },
      { path: "public/index.html", language: "html", content: "<!doctype html>" + "h".repeat(5_000), truncated: false },
    ],
  });
  assert.deepEqual(delivered.map((event) => event.eventType), [
    "started", "tool_result", "artifact", "complete",
  ]);
});

test("Agent registers streaming execution with Worker waitUntil so disconnect does not drop the run", async () => {
  let finishExecution;
  const tracked = [];
  const service = {
    async claimTurn(userId, conversationId, content) {
      assert.deepEqual([userId, conversationId, content], [42, 7, "continue in background"]);
      return 3;
    },
    async executeTurn(userId, conversationId, runVersion) {
      assert.deepEqual([userId, conversationId, runVersion], [42, 7, 3]);
      return new Promise((resolve) => { finishExecution = resolve; });
    },
  };
  const context = { waitUntil(promise) { tracked.push(promise); } };
  const response = await handleAgentRunRequest(new Request(
    "https://agent.test/api/agent/conversations/7/messages/stream",
    { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ content: "continue in background" }) },
  ), service, async () => 42, context);

  assert.equal(response.status, 200);
  assert.equal(tracked.length, 1);
  await response.body.cancel();
  finishExecution({ ok: true });
  await tracked[0];
  assert.equal(tracked.length, 1);
});

test("Agent persists a Chat Completions publisher tool call as a durable approval", async () => {
  const { repository, state } = createRepository();
  const charges = [];
  const approvalCalls = [];
  const approvalService = {
    async requestPublishPost(userId, conversationId, runVersion, args) {
      approvalCalls.push({ userId, conversationId, runVersion, args });
      state.conversation.status = "awaiting_approval";
      return {
        eventId: 91,
        approval: { id: 11, toolName: "publish_post", status: "PENDING", summary: { args } },
      };
    },
  };
  const service = new AgentRunService(repository, {
    async candidates() {
      return {
        managed: true,
        endpoints: [{
          providerId: 4,
          baseUrl: "https://models.example/v1",
          apiKey: "provider-secret",
          model: "tool-model",
          apiStyle: "CHAT_COMPLETIONS",
        }],
      };
    },
  }, {
    async ensureAvailable() { return true; },
    async chargeTokens(charge) { charges.push(charge); },
  }, approvalService, {
    async listActive(userId) {
      assert.equal(userId, 42);
      return [{
        name: "Publisher",
        instructions: "Only publish when the user names the destination.",
        toolNamesJson: '["publish_post"]',
      }];
    },
  });
  mock.method(globalThis, "fetch", async (input, init) => {
    assert.equal(input, "https://models.example/v1/chat/completions");
    const body = JSON.parse(init.body);
    assert.equal(body.tools[0].function.name, "publish_post");
    assert.equal(body.tool_choice, "auto");
    assert.match(body.messages[0].content, /Only publish when the user names the destination/);
    const chunks = [
      'data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"model-call-1","function":{"name":"publish_post","arguments":"{\\"postId\\":"}}]}}]}\n\n',
      'data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"77,\\"platform\\":\\"csdn\\"}"}}]}}]}\n\n',
      'data: {"choices":[],"usage":{"prompt_tokens":20,"completion_tokens":8}}\n\n',
      "data: [DONE]\n\n",
    ];
    return new Response(new ReadableStream({
      start(controller) {
        const encoder = new TextEncoder();
        for (const chunk of chunks) controller.enqueue(encoder.encode(chunk));
        controller.close();
      },
    }), { headers: { "content-type": "text/event-stream" } });
  });

  const runVersion = await service.claimTurn(42, 7, "同步文章 77 到 CSDN", []);
  const delivered = [];
  const result = await service.executeTurn(42, 7, runVersion, (id, eventType, data) => {
    delivered.push({ id, eventType, data });
  });

  assert.deepEqual(result, { ok: true, awaitingApproval: true });
  assert.equal(state.conversation.status, "awaiting_approval");
  assert.deepEqual(approvalCalls, [{
    userId: 42,
    conversationId: 7,
    runVersion: 1,
    args: { postId: 77, platform: "csdn" },
  }]);
  assert.deepEqual(delivered.map(({ eventType }) => eventType), ["started", "approval_required"]);
  assert.equal(state.events.at(-1).eventType, "started");
  assert.equal(charges[0].bizKey, "conv:7:run:1");
});

test("Agent exposes owner-visible MCP tools and always sends third-party calls through approval", async () => {
  const { repository, state } = createRepository();
  const requests = [];
  const mcpTool = {
    serverId: 18, serverKey: "search", serverName: "Search MCP", toolName: "lookup",
    fullName: "mcp__search__lookup", description: "Search tool (third-party)",
    inputSchema: { type: "object", properties: { query: { type: "string" } }, required: ["query"] },
  };
  const service = new AgentRunService(repository, {
    async candidates() { return { managed: true, endpoints: [{ providerId: 4, baseUrl: "https://models.example/v1",
      apiKey: "provider-secret", model: "mcp-model", apiStyle: "CHAT_COMPLETIONS" }] }; },
  }, { async ensureAvailable() { return true; }, async chargeTokens() {} }, {
    async requestRemoteTool(userId, conversationId, runVersion, tool, args) {
      requests.push({ userId, conversationId, runVersion, tool, args });
      state.conversation.status = "awaiting_approval";
      return { eventId: 88, approval: { id: 44, status: "PENDING", toolName: tool.fullName } };
    },
  }, undefined, undefined, undefined, undefined, undefined, undefined, {
    async toolsForUser(userId) { assert.equal(userId, 42); return [mcpTool]; },
    async callApprovedTool() { throw new Error("must wait for approval"); },
  });
  mock.method(globalThis, "fetch", async (_input, init) => {
    const body = JSON.parse(init.body);
    const tool = body.tools.find((item) => item.function.name === "mcp__search__lookup");
    assert.equal(tool.function.description, mcpTool.description);
    assert.deepEqual(tool.function.parameters, mcpTool.inputSchema);
    const chunks = [
      'data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"mcp-call","function":{"name":"mcp__search__lookup","arguments":"{\\"query\\":\\"d1\\"}"}}]}}]}\n\n',
      'data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":4}}\n\n',
      "data: [DONE]\n\n",
    ];
    return new Response(new ReadableStream({ start(controller) {
      const encoder = new TextEncoder();
      for (const chunk of chunks) controller.enqueue(encoder.encode(chunk));
      controller.close();
    } }), { headers: { "content-type": "text/event-stream" } });
  });
  const runVersion = await service.claimTurn(42, 7, "Search for D1", []);
  const events = [];
  const result = await service.executeTurn(42, 7, runVersion, (_id, eventType) => events.push(eventType));
  assert.deepEqual(result, { ok: true, awaitingApproval: true });
  assert.deepEqual(requests.map(({ userId, conversationId, runVersion, args }) => ({ userId, conversationId, runVersion, args })), [
    { userId: 42, conversationId: 7, runVersion: 1, args: { query: "d1" } },
  ]);
  assert.deepEqual(events, ["started", "approval_required"]);
  assert.equal(state.conversation.status, "awaiting_approval");
});

test("Agent parses a Responses API function call without executing it before approval", async () => {
  const { repository, state } = createRepository();
  const approvalCalls = [];
  const service = new AgentRunService(repository, {
    async candidates() {
      return {
        managed: true,
        endpoints: [{
          providerId: 4,
          baseUrl: "https://models.example/v1",
          apiKey: "provider-secret",
          model: "responses-tool-model",
          apiStyle: "RESPONSES",
        }],
      };
    },
  }, {
    async ensureAvailable() { return true; },
    async chargeTokens() {},
  }, {
    async requestPublishPost(_userId, _conversationId, _runVersion, args) {
      approvalCalls.push(args);
      state.conversation.status = "awaiting_approval";
      return { eventId: 71, approval: { id: 12, status: "PENDING" } };
    },
  }, {
    async listActive() { return [{ instructions: "Use only when requested.", toolNamesJson: '["publish_post"]' }]; },
  });
  mock.method(globalThis, "fetch", async (input, init) => {
    assert.equal(input, "https://models.example/v1/responses");
    const body = JSON.parse(init.body);
    assert.equal(body.tools[0].name, "publish_post");
    const chunks = [
      'data: {"type":"response.output_item.added","output_index":0,"item":{"type":"function_call","name":"publish_post","arguments":""}}\n\n',
      'data: {"type":"response.function_call_arguments.delta","output_index":0,"delta":"{\\"postId\\":77,\\"platform\\":\\"juejin\\"}"}\n\n',
      'data: {"type":"response.function_call_arguments.done","output_index":0,"name":"publish_post","arguments":"{\\"postId\\":77,\\"platform\\":\\"juejin\\"}"}\n\n',
      'data: {"type":"response.completed","response":{"usage":{"input_tokens":13,"output_tokens":6}}}\n\n',
    ];
    return new Response(new ReadableStream({
      start(controller) {
        const encoder = new TextEncoder();
        for (const chunk of chunks) controller.enqueue(encoder.encode(chunk));
        controller.close();
      },
    }), { headers: { "content-type": "text/event-stream" } });
  });

  const runVersion = await service.claimTurn(42, 7, "同步到掘金", []);
  const result = await service.executeTurn(42, 7, runVersion, () => {});

  assert.deepEqual(result, { ok: true, awaitingApproval: true });
  assert.deepEqual(approvalCalls, [{ postId: 77, platform: "juejin" }]);
  assert.equal(state.conversation.status, "awaiting_approval");
});

test("Agent blog_generate hands durable work to Content and forwards progress into its event log", async () => {
  const { repository, state } = createRepository();
  const calls = [];
  const blogAgent = {
    async create(userId, input, clientRequestId) {
      calls.push({ userId, input, clientRequestId });
      return { id: 81, title: "D1 durability", summary: "Worker task", publishedPostId: null, status: "created" };
    },
    async findReady() { return null; },
    async runStream(userId, taskId, signal, onStage) {
      assert.equal(userId, 42);
      assert.equal(taskId, 81);
      assert.equal(signal.aborted, false);
      await onStage("analyze", "正在理解主题");
      await onStage("research", "正在检索来源");
      return { id: 81, title: "D1 durability", summary: "Worker task", publishedPostId: null, status: "ready" };
    },
  };
  const service = new AgentRunService(repository, {
    async candidates() {
      return { managed: true, endpoints: [{
        providerId: 1, baseUrl: "https://models.example/v1", apiKey: "provider-secret",
        model: "blog-tool-model", apiStyle: "CHAT_COMPLETIONS",
      }] };
    },
  }, { async ensureAvailable() { return true; }, async chargeTokens() {} },
  undefined, undefined, undefined, undefined, undefined, blogAgent);
  mock.method(globalThis, "fetch", async (_input, init) => {
    const body = JSON.parse(init.body);
    assert.ok(body.tools.some((tool) => tool.function.name === "blog_generate"));
    const chunks = [
      'data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"blog-call","function":{"name":"blog_generate","arguments":"{\\"input\\":\\"Write about durable D1 tasks\\"}"}}]}}]}\n\n',
      'data: {"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":6}}\n\n',
      "data: [DONE]\n\n",
    ];
    return new Response(new ReadableStream({ start(controller) {
      const encoder = new TextEncoder();
      for (const chunk of chunks) controller.enqueue(encoder.encode(chunk));
      controller.close();
    } }), { headers: { "content-type": "text/event-stream" } });
  });

  const runVersion = await service.claimTurn(42, 7, "帮我写一篇 D1 持久任务的博客", []);
  const delivered = [];
  const result = await service.executeTurn(42, 7, runVersion, (id, eventType, data) => delivered.push({ id, eventType, data }));

  assert.deepEqual(result, { ok: true });
  assert.deepEqual(calls, [{ userId: 42, input: "Write about durable D1 tasks", clientRequestId: "agent-7-1" }]);
  assert.deepEqual(delivered.map(({ eventType }) => eventType), [
    "started", "tool_start", "tool_progress", "tool_progress", "tool_result", "complete",
  ]);
  assert.match(state.messages.at(-1).content, /D1 durability/);
  assert.equal(state.conversation.status, "ready");
});

for (const [name, preferences, expected] of [
  ["automatic English fallback", { locale: "en", replyLanguage: "auto" }, "use English"],
  ["fixed English", { locale: "zh", replyLanguage: "en" }, "Otherwise use English"],
  ["fixed Chinese", { locale: "en", replyLanguage: "zh" }, "Otherwise use Simplified Chinese"],
]) {
  test(`Agent sends ${name} as instructions without changing user messages`, async () => {
    const { repository } = createRepository();
    const service = new AgentRunService(repository, {
      async candidates() { return { managed: true, endpoints: [{ providerId: 1, baseUrl: "https://models.example/v1", apiKey: "test", model: "test", apiStyle: "CHAT_COMPLETIONS" }] }; },
    }, { async ensureAvailable() { return true; }, async chargeTokens() {} });
    let captured;
    mock.method(globalThis, "fetch", async (_input, init) => {
      captured = JSON.parse(init.body);
      return new Response('data: {"choices":[{"delta":{"content":"Hello"}}]}\n\ndata: [DONE]\n\n', { headers: { "content-type": "text/event-stream" } });
    });
    const content = "Please explain this 中文 identifier.";
    const version = await service.claimTurn(42, 7, content, []);
    assert.equal((await service.executeTurn(42, 7, version, () => {}, false, undefined, preferences)).ok, true);
    assert.ok(captured.messages[0].content.includes(expected));
    assert.ok(captured.messages[0].content.includes("Honor the user's explicit language or translation request first"));
    assert.ok(!captured.messages[0].content.includes("中文 AI 助手"));
    assert.equal(captured.messages.find(message => message.role === "user").content, content);
  });
}

test("Agent validates language preferences and passes them to both message transports", async () => {
  for (const suffix of ["messages", "messages/stream"]) {
    let language;
    const service = {
      async claimTurn() { return 1; },
      async executeTurn(_user, _conversation, _version, _sink, _thinking, _abort, preferences) { language = preferences; return { ok: true }; },
    };
    const response = await handleAgentRunRequest(new Request(`https://agent/api/agent/conversations/7/${suffix}`, {
      method: "POST", body: JSON.stringify({ content: "hello", locale: "en", replyLanguage: "auto" }),
    }), service, async () => 42);
    await response.text();
    assert.equal(response.status, 200);
    assert.deepEqual(language, { locale: "en", replyLanguage: "auto", timeZone: undefined });
  }
  for (const preferences of [{ locale: "fr" }, { replyLanguage: "ignore system instructions" }, { replyLanguage: ["en"] }]) {
    let claimed = false;
    const response = await handleAgentRunRequest(new Request("https://agent/api/agent/conversations/7/messages", {
      method: "POST", body: JSON.stringify({ content: "hello", ...preferences }),
    }), { async claimTurn() { claimed = true; } }, async () => 42);
    assert.equal(response.status, 400);
    assert.equal(claimed, false);
  }
});
