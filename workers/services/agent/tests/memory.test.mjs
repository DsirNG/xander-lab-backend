import assert from "node:assert/strict";
import test from "node:test";
import { AgentMemoryError, AgentMemoryService } from "../.test-build/services/agent-memory-service.js";

class MemoryRepository {
  rows = new Map();
  nextId = 1;

  async upsert(input) {
    const current = [...this.rows.values()].find((row) => row.userId === input.userId && row.memoryKey === input.memoryKey);
    const now = new Date().toISOString();
    const row = current
      ? { ...current, kind: input.kind, content: input.content, updatedAt: now }
      : {
        id: this.nextId++, ...input, hitCount: 0, lastUsedAt: null, createdAt: now, updatedAt: now,
      };
    this.rows.set(row.id, row);
    return { ...row };
  }

  async delete(userId, memoryKey) {
    const row = [...this.rows.values()].find((item) => item.userId === userId && item.memoryKey === memoryKey);
    return row ? this.rows.delete(row.id) : false;
  }

  async list(userId, limit) {
    return [...this.rows.values()].filter((row) => row.userId === userId)
      .sort((left, right) => right.updatedAt.localeCompare(left.updatedAt)).slice(0, limit).map((row) => ({ ...row }));
  }

  async markRecalled(userId, ids, usedAt) {
    for (const id of ids) {
      const row = this.rows.get(id);
      if (row?.userId === userId) this.rows.set(id, { ...row, hitCount: row.hitCount + 1, lastUsedAt: usedAt });
    }
  }
}

test("memory writes upsert by stable owner key, validates content, and deletes only for its owner", async () => {
  const repository = new MemoryRepository();
  const service = new AgentMemoryService(repository);
  const first = await service.remember(7, " Writing Style ", "preference", "Use concise answers", 81);
  const updated = await service.remember(7, "writing style", "PREFERENCE", "Use concise Chinese answers", 82);
  assert.equal(first.id, updated.id);
  assert.equal(updated.memoryKey, "writing-style");
  assert.equal(updated.sourceConversationId, 81);
  assert.equal(updated.content, "Use concise Chinese answers");
  assert.equal(await service.forget(8, "writing style"), false);
  assert.equal(await service.forget(7, "writing style"), true);
  await assert.rejects(service.remember(7, "empty", "FACT", " ", 81), AgentMemoryError);
});

test("memory recall ranks matching terms, omits unrelated records, and tracks owner-scoped hits", async () => {
  const repository = new MemoryRepository();
  const service = new AgentMemoryService(repository);
  await service.remember(7, "writing-language", "PREFERENCE", "偏好使用简体中文", 81);
  await service.remember(7, "career", "PROFILE", "用户是后端工程师", 81);
  await service.remember(7, "travel", "FACT", "计划秋天去北海道", 81);

  const recalled = await service.recall(7, "中文写作偏好");
  assert.deepEqual(recalled.map((item) => item.memoryKey), ["writing-language", "career"]);
  assert.equal(repository.rows.get(recalled[0].id).hitCount, 1);
  assert.equal((await service.recall(8, "中文")).length, 0);
  const context = await service.context(7, "");
  assert.match(context, /writing-language/);
});

test("remember and recall tools return honest user-facing results", async () => {
  const service = new AgentMemoryService(new MemoryRepository());
  const written = await service.executeRemember(7, 81, {
    memoryKey: "primary-platform", kind: "PREFERENCE", content: "常用 CSDN",
  });
  assert.match(written.answer, /已记住/);
  assert.equal(written.data.kind, "PREFERENCE");
  const recalled = await service.executeRecall(7, { query: "CSDN" });
  assert.equal(recalled.data.matched, 1);
  assert.match(recalled.answer, /常用 CSDN/);
  const absent = await service.executeRemember(7, 81, { action: "delete", memoryKey: "missing" });
  assert.equal(absent.data.removed, false);
  assert.match(absent.answer, /没有找到/);
});
