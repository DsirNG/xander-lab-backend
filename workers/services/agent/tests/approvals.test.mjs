import assert from "node:assert/strict";
import test from "node:test";
import { handleAgentApprovalRequest } from "../.test-build/controllers/agent-approval-controller.js";
import { AgentToolApprovalService } from "../.test-build/services/agent-tool-approval-service.js";
import { AgentContentToolsError } from "../.test-build/services/agent-content-tools-service.js";

function createHarness({ existing = null, syncResult = null, syncError = null, lookupError = null, mcp = null, contentTools = null } = {}) {
  const state = { pending: null, decision: null, failed: [], completed: [], finished: [], syncs: [] };
  const repository = {
    async createPendingApproval(input) {
      state.pending = {
        id: 11,
        ...input,
        summaryJson: input.summaryJson,
        status: "PENDING",
        decisionReason: null,
        decidedAt: null,
        createdAt: "2026-10-10T00:00:00.000Z",
        updatedAt: "2026-10-10T00:00:00.000Z",
      };
      return { approval: state.pending, eventId: 52 };
    },
    async listApprovals(userId, conversationId) {
      return userId === 42 && conversationId === 7 ? [state.pending].filter(Boolean) : null;
    },
    async decideApproval(userId, conversationId, approvalId, approved, reason) {
      if (userId !== 42 || conversationId !== 7 || approvalId !== 11) return { kind: "not_found" };
      if (state.pending.status !== "PENDING") return { kind: "conflict" };
      state.pending.status = approved ? "APPROVED" : "REJECTED";
      state.pending.decisionReason = reason;
      state.decision = approved;
      return { kind: "decided", approval: state.pending, argumentsJson: state.pending.argumentsJson };
    },
    async completeInvocation(...args) { state.completed.push(args); return 54; },
    async failInvocation(...args) { state.failed.push(args); return 55; },
  };
  const turns = { async finishTurn(...args) { state.finished.push(args); } };
  const publication = {
    async findSucceeded(...args) {
      state.lookup = args;
      if (lookupError) throw lookupError;
      return existing;
    },
    async sync(...args) {
      state.syncs.push(args);
      if (syncError) throw syncError;
      return syncResult;
    },
  };
  return { service: new AgentToolApprovalService(repository, turns, publication, mcp, contentTools), state };
}

test("publisher approval stores a canonical, owner-bound summary and fingerprint", async () => {
  const { service, state } = createHarness();
  const requested = await service.requestPublishPost(42, 7, 3, { platform: "csdn", postId: 77 });

  assert.equal(requested.eventId, 52);
  assert.equal(requested.approval.id, 11);
  assert.equal(requested.approval.status, "PENDING");
  assert.equal(state.pending.toolName, "publish_post");
  assert.equal(state.pending.argumentsJson, '{"platform":"csdn","postId":77}');
  assert.match(state.pending.requestFingerprint, /^[a-f0-9]{64}$/);
  assert.deepEqual(requested.approval.summary, {
    tool: "publish_post",
    reason: "将文章同步到 csdn，可能公开内容并触发平台操作",
    args: { postId: 77, platform: "csdn" },
  });
});

test("publisher approval rejects unsupported platforms and extra model arguments", async () => {
  const { service } = createHarness();
  await assert.rejects(service.requestPublishPost(42, 7, 3, { postId: 77, platform: "local" }),
    (error) => error.status === 400);
  await assert.rejects(service.requestPublishPost(42, 7, 3, { postId: 77, platform: "juejin", userId: 99 }),
    (error) => error.status === 400);
});

test("rejecting an approval records the decision and never contacts Content", async () => {
  const { service, state } = createHarness();
  await service.requestPublishPost(42, 7, 3, { postId: 77, platform: "csdn" });
  const result = await service.decide(42, 7, 11, false, "not now");

  assert.equal(result.status, "REJECTED");
  assert.equal(result.decisionReason, "not now");
  assert.equal(state.syncs.length, 0);
  assert.equal(state.failed[0][4], "用户拒绝审批");
  assert.equal(state.finished[0][3], "好的，已取消这次文章同步操作。");
});

test("approving an already-succeeded publication reconciles it without a duplicate sync", async () => {
  const alreadyPublished = { platform: "csdn", externalId: "9007199254740993", url: "https://csdn.test/post" };
  const { service, state } = createHarness({ existing: alreadyPublished });
  await service.requestPublishPost(42, 7, 3, { postId: 77, platform: "csdn" });
  const result = await service.decide(42, 7, 11, true, null);

  assert.equal(result.status, "APPROVED");
  assert.equal(state.syncs.length, 0);
  assert.deepEqual(state.completed[0].at(-1), alreadyPublished);
  assert.match(state.finished[0][3], /https:\/\/csdn\.test\/post/);
});

test("approving a new publication calls Content once and persists the result", async () => {
  const published = { platform: "csdn", externalId: "post-77", url: null };
  const { service, state } = createHarness({ syncResult: published });
  await service.requestPublishPost(42, 7, 3, { postId: 77, platform: "csdn" });
  await service.decide(42, 7, 11, true, null);

  assert.deepEqual(state.lookup, [42, 77, "csdn"]);
  assert.deepEqual(state.syncs, [[42, 77, "csdn"]]);
  assert.deepEqual(state.completed[0].at(-1), published);
  assert.match(state.finished[0][3], /平台文章 ID：post-77/);
});

test("an unconfirmed remote result is journaled as unknown and never retried", async () => {
  const { service, state } = createHarness({
    syncError: new Error("connection lost"),
  });
  await service.requestPublishPost(42, 7, 3, { postId: 77, platform: "csdn" });
  await service.decide(42, 7, 11, true, null);

  assert.equal(state.syncs.length, 1);
  assert.equal(state.failed[0][5], true);
  assert.equal(state.finished[0][3], "发布请求结果暂时无法确认，系统没有自动重试，避免重复发文。请先检查目标平台，再决定是否重新发起。");
});

test("remote MCP tools require owner approval, then execute once with the immutable argument snapshot", async () => {
  const calls = [];
  const mcp = {
    async callApprovedTool(...args) { calls.push(args); return { text: ["remote result"] }; },
  };
  const { service, state } = createHarness({ mcp });
  const tool = { fullName: "mcp__search__lookup", serverId: 18, serverName: "Search MCP", toolName: "lookup" };
  const request = await service.requestRemoteTool(42, 7, 3, tool, { query: "d1" });
  assert.equal(state.pending.toolName, tool.fullName);
  assert.deepEqual(JSON.parse(state.pending.argumentsJson), {
    serverId: 18, remoteToolName: "lookup", arguments: { query: "d1" },
  });
  assert.match(request.approval.summary.reason, /第三方 MCP/);
  assert.deepEqual(calls, []);

  await service.decide(42, 7, 11, true, null);
  assert.deepEqual(calls, [[42, 18, "lookup", { query: "d1" }]]);
  assert.equal(state.completed.length, 1);
  assert.match(state.finished[0][3], /remote result/);
});

test("remote MCP tool execution with an uncertain outcome is never retried", async () => {
  let calls = 0;
  const mcp = { async callApprovedTool() { calls += 1; throw new Error("connection lost after send"); } };
  const { service, state } = createHarness({ mcp });
  await service.requestRemoteTool(42, 7, 3,
    { fullName: "mcp__search__lookup", serverId: 18, serverName: "Search MCP", toolName: "lookup" },
    { query: "d1" });
  await service.decide(42, 7, 11, true, null);
  assert.equal(calls, 1);
  assert.equal(state.failed[0][5], true);
  assert.match(state.finished[0][3], /没有自动重试/);
});

test("schedule_plan stores an immutable owner approval and executes once with its invocation id", async () => {
  const calls = [];
  const contentTools = { async executeApprovedMutation(...args) {
    calls.push(args);
    return { data: { ok: true, plan: { id: 18, topic: "D1 的正确用法" } }, answer: "定时发文计划已创建：D1 的正确用法。" };
  } };
  const { service, state } = createHarness({ contentTools });
  const requested = await service.requestContentMutation(42, 7, 3, "schedule_plan", {
    topic: "D1 的正确用法", triggerTime: "09:30",
  });

  assert.equal(state.pending.toolName, "schedule_plan");
  assert.deepEqual(JSON.parse(state.pending.argumentsJson), { topic: "D1 的正确用法", triggerTime: "09:30" });
  assert.match(requested.approval.summary.reason, /创建自动发文计划/);
  assert.equal(calls.length, 0);
  await service.decide(42, 7, 11, true, null);
  assert.equal(calls.length, 1);
  assert.equal(calls[0][0], 42);
  assert.equal(calls[0][1], "schedule_plan");
  assert.equal(calls[0][3], state.pending.invocationId);
  assert.equal(state.completed.length, 1);
  assert.equal(state.finished[0][3], "定时发文计划已创建：D1 的正确用法。");
});

test("approved idempotent reminder creation retries a transient Content response with the same key", async () => {
  const calls = [];
  const contentTools = { async executeApprovedMutation(...args) {
    calls.push(args);
    if (calls.length === 1) throw new AgentContentToolsError(503, "Content unavailable");
    return { data: { ok: true, reminder: { id: 19 } }, answer: "定时邮件提醒已创建。" };
  } };
  const { service, state } = createHarness({ contentTools });
  await service.requestContentMutation(42, 7, 3, "email_reminder", {
    recipientEmail: "owner@example.com", subject: "周报", message: "本周进展", scheduledAt: "2026-10-12T09:00:00+08:00",
  });
  await service.decide(42, 7, 11, true, null);

  assert.equal(calls.length, 2);
  assert.equal(calls[0][3], calls[1][3]);
  assert.equal(state.completed.length, 1);
  assert.equal(state.failed.length, 0);
});

test("approval routes authenticate, scope by owner, and validate decisions", async () => {
  const calls = [];
  const service = {
    async list(userId, conversationId) {
      calls.push(["list", userId, conversationId]);
      return userId === 42 ? [] : null;
    },
    async decide(...args) {
      calls.push(["decide", ...args]);
      return { id: args[2], status: args[3] ? "APPROVED" : "REJECTED" };
    },
  };
  const user = async () => 42;
  const listResponse = await handleAgentApprovalRequest(
    new Request("https://agent.test/api/agent/conversations/7/approvals"), service, user,
  );
  const decisionResponse = await handleAgentApprovalRequest(new Request(
    "https://agent.test/api/agent/conversations/7/approvals/11/decision",
    { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ approved: false, reason: "cancel" }) },
  ), service, user);
  const invalidResponse = await handleAgentApprovalRequest(new Request(
    "https://agent.test/api/agent/conversations/7/approvals/11/decision",
    { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ approved: "yes" }) },
  ), service, user);
  const unauthorized = await handleAgentApprovalRequest(
    new Request("https://agent.test/api/agent/conversations/7/approvals"), service,
    async () => Response.json({ code: 401 }, { status: 401 }),
  );

  assert.equal(listResponse.status, 200);
  assert.deepEqual((await listResponse.json()).data, []);
  assert.equal(decisionResponse.status, 200);
  assert.deepEqual((await decisionResponse.json()).data, { id: 11, status: "REJECTED" });
  assert.equal(invalidResponse.status, 400);
  assert.equal(unauthorized.status, 401);
  assert.deepEqual(calls, [["list", 42, 7], ["decide", 42, 7, 11, false, "cancel", null]]);
});
