import assert from "node:assert/strict";
import test from "node:test";
import { D1BlogPlanMapper } from "../.test-build/mappers/d1-blog-plan-mapper.js";

test("D1 blog-plan mapper inserts a batch through one transactional D1 batch call", async () => {
  const statements = [];
  let batchCalls = 0;
  const db = {
    prepare(query) {
      const statement = {
        query,
        values: [],
        bind(...values) { this.values = values; return this; },
      };
      statements.push(statement);
      return statement;
    },
    async batch(batch) {
      batchCalls += 1;
      assert.equal(batch.length, 2);
      return batch.map((_statement, index) => ({
        success: true, meta: { changes: 1 }, results: [{ id: index + 10 }],
      }));
    },
  };
  const mapper = new D1BlogPlanMapper(db);
  const ids = await mapper.createMany([
    { userId: 42, clientRequestId: null, topic: "First", audience: "", tone: "", scheduleType: "ONCE",
      scheduledDate: "2026-10-11", aiOption: "DEEP", knowledgeMaterialId: null, autoPublish: true,
      timezone: "Asia/Singapore", triggerTime: "09:00", triggerTimes: ["09:00"], topics: [],
      nextTopicIndex: 0, syncCsdn: false, syncJuejin: false, runOnce: true, nextRunAt: "2026-10-11T01:00:00.000Z",
      lastRunAt: null, status: "ACTIVE", errorMessage: null, deletedAt: null },
    { userId: 42, clientRequestId: null, topic: "Second", audience: "", tone: "", scheduleType: "ONCE",
      scheduledDate: "2026-10-12", aiOption: "DEEP", knowledgeMaterialId: null, autoPublish: true,
      timezone: "Asia/Singapore", triggerTime: "09:00", triggerTimes: ["09:00"], topics: [],
      nextTopicIndex: 0, syncCsdn: false, syncJuejin: false, runOnce: true, nextRunAt: "2026-10-12T01:00:00.000Z",
      lastRunAt: null, status: "ACTIVE", errorMessage: null, deletedAt: null },
  ]);
  assert.deepEqual(ids, [10, 11]);
  assert.equal(batchCalls, 1);
  assert.ok(statements.every((statement) => statement.query.includes("RETURNING id")));
});
