import assert from "node:assert/strict";
import test from "node:test";
import { D1AgentPlanMapper } from "../.test-build/mappers/d1-agent-plan-mapper.js";
import { AgentPlanError, AgentPlanService } from "../.test-build/services/agent-plan-service.js";

test("Agent plan replaces the full list and persists the replay event", async () => {
  let savedItems;
  const service = new AgentPlanService({
    async replace(userId, conversationId, runVersion, items) {
      assert.deepEqual([userId, conversationId, runVersion], [42, 7, 3]);
      savedItems = items;
      return 11;
    },
  });
  const result = await service.replace(42, 7, 3, { items: [
    { title: "Inspect migration", status: "DONE", note: "verified" },
    { title: "Finish local verification", status: "IN_PROGRESS" },
  ] });
  assert.equal(result.eventId, 11);
  assert.deepEqual(savedItems, [
    { title: "Inspect migration", status: "DONE", note: "verified" },
    { title: "Finish local verification", status: "IN_PROGRESS" },
  ]);
  assert.equal(result.result.total, 2);
  assert.equal(result.result.unfinished, 1);
});

test("Agent plan validates status limits, a single active step, and dropped-step notes", async () => {
  const service = new AgentPlanService({ async replace() { return 1; } });
  await assert.rejects(() => service.replace(42, 7, 1, { items: [
    { title: "First", status: "IN_PROGRESS" }, { title: "Second", status: "IN_PROGRESS" },
  ] }), (error) => error instanceof AgentPlanError && error.status === 400);
  await assert.rejects(() => service.replace(42, 7, 1, { items: [{ title: "Drop", status: "DROPPED" }] }),
    /必须说明原因/);
  await assert.rejects(() => service.replace(42, 7, 1, { items: Array.from({ length: 21 }, (_, i) => ({ title: String(i), status: "PENDING" })) }),
    /最多包含 20 个步骤/);
});

test("D1 plan mapper writes conversation state, assistant message, and SSE event in one batch", async () => {
  const statements = [];
  const db = {
    prepare(sql) {
      const statement = { sql, values: [], bind(...values) { this.values = values; return this; } };
      statements.push(statement);
      return statement;
    },
    async batch(batch) {
      assert.equal(batch.length, 3);
      return batch.map((_, index) => ({ success: true, meta: { changes: 1 }, results: index === 2 ? [{ id: 29 }] : [] }));
    },
  };
  const first = { id: 29 };
  const originalPrepare = db.prepare.bind(db);
  db.prepare = (sql) => {
    const statement = originalPrepare(sql);
    statement.first = async () => first;
    return statement;
  };
  const mapper = new D1AgentPlanMapper(db);
  const result = await mapper.replace(42, 7, 4, [{ title: "Verify", status: "IN_PROGRESS" }]);
  assert.equal(result, 29);
  assert.match(statements[0].sql, /UPDATE agent_conversation SET plan_json/);
  assert.match(statements[1].sql, /INSERT INTO agent_message/);
  assert.match(statements[2].sql, /INSERT INTO agent_event/);
  assert.equal(statements[0].values[0], JSON.stringify([{ title: "Verify", status: "IN_PROGRESS" }]));
});
