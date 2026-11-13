import test from "node:test";
import assert from "node:assert/strict";
import { AgentToolExecutionError, AgentToolExecutionPipeline } from "../.test-build/services/agent-tool-runtime.js";
import { AgentToolRegistry } from "../.test-build/services/agent-tool-registry.js";
import { AgentToolDispatcher } from "../.test-build/services/agent-tool-dispatcher.js";

const context = {
  userId: 7, conversationId: 11, runVersion: 2, invocationId: "inv-1", idempotencyKey: "key-1",
  signal: new AbortController().signal,
};

function tool(name, readOnly, execute) {
  return { definition: () => ({ name, description: name, parameters: { type: "object" }, readOnly }), execute };
}

test("tool runtime runs read-only batches concurrently and normalizes results", async () => {
  let active = 0;
  let maxActive = 0;
  const tools = new Map([
    ["read_a", tool("read_a", true, async () => {
      active += 1; maxActive = Math.max(maxActive, active);
      await new Promise((resolve) => setTimeout(resolve, 10)); active -= 1;
      return { ok: true, data: "a" };
    })],
    ["read_b", tool("read_b", true, async () => {
      active += 1; maxActive = Math.max(maxActive, active);
      await new Promise((resolve) => setTimeout(resolve, 10)); active -= 1;
      return { ok: true, data: "b" };
    })],
  ]);
  const result = await new AgentToolExecutionPipeline(tools).executeBatch([
    { name: "read_a", arguments: {} }, { name: "read_b", arguments: {} },
  ], context);
  assert.equal(maxActive, 2);
  assert.deepEqual(result.map((item) => item.data), ["a", "b"]);
});

test("tool runtime rejects an approval batch before starting any tool", async () => {
  let started = 0;
  const tools = new Map([
    ["write", tool("write", false, async () => { started += 1; return { ok: true }; })],
    ["read", tool("read", true, async () => { started += 1; return { ok: true }; })],
  ]);
  const pipeline = new AgentToolExecutionPipeline(tools, {
    requiresApproval: (_tool, call) => call.name === "write",
  });
  await assert.rejects(
    pipeline.executeBatch([{ name: "write", arguments: {} }, { name: "read", arguments: {} }], context),
    (error) => error instanceof AgentToolExecutionError && error.code === "APPROVAL_REQUIRED",
  );
  assert.equal(started, 0);
});

test("tool registry keeps the first definition and isolates optional source failures", async () => {
  const registry = await AgentToolRegistry.fromSources([
    { tools: () => [{ name: "stable", description: "first", parameters: {} }] },
    { tools: () => { throw new Error("optional source unavailable"); } },
    { tools: () => [{ name: "stable", description: "duplicate", parameters: {} }, { name: "dynamic", description: "remote", parameters: {} }] },
  ]);
  assert.equal(registry.get("stable")?.description, "first");
  assert.equal(registry.get("dynamic")?.description, "remote");
  assert.equal(registry.list().length, 2);
});

test("tool dispatcher reuses one pipeline for registered and factory tools", async () => {
  const dispatcher = new AgentToolDispatcher(new Map());
  dispatcher.registerFactory("dynamic", () => tool("dynamic", true, async () => ({ ok: true, data: "ok" })));
  const result = await dispatcher.execute({ name: "dynamic", arguments: {} }, context);
  assert.equal(result.data, "ok");
  assert.equal(dispatcher.has("dynamic"), true);
});

test("tool dispatcher can route orchestration through a registered handler", async () => {
  const dispatcher = new AgentToolDispatcher(new Map([["handled", tool("handled", true, async () => ({ data: "runtime" }))]]));
  dispatcher.registerHandler("handled", async () => ({ ok: true, data: "handler" }));
  const result = await dispatcher.execute({ name: "handled", arguments: {} }, context);
  assert.equal(result.data, "handler");
});
