import assert from "node:assert/strict";
import test from "node:test";
import { RecitationError, RecitationService } from "../.test-build/services/recitation-service.js";

class MemoryRecitationRepository {
  rows = new Map();
  calls = [];
  nextId = 1;

  async createMaterial(input) {
    const now = "2026-10-10T00:00:00.000Z";
    const row = {
      ...input,
      id: this.nextId++,
      masteryScore: 0,
      masteryLevel: "NEW",
      reviewCount: 0,
      lastReviewAt: null,
      nextReviewAt: null,
      archivedAt: null,
      createdAt: now,
      updatedAt: now,
    };
    this.rows.set(row.id, row);
    return row;
  }

  async findMaterial(userId, materialId) {
    const row = this.rows.get(materialId);
    return row?.userId === userId ? { ...row } : null;
  }

  async searchMaterials(userId, query) {
    this.calls.push({ method: "search", query });
    return [...this.rows.values()].filter((row) => row.userId === userId
      && (query.archive === "ALL" || (query.archive === "ACTIVE" ? !row.archivedAt : row.archivedAt)))
      .slice(0, query.limit).map((row) => ({ ...row }));
  }

  async updateMaterial(userId, materialId, patch) {
    const row = this.rows.get(materialId);
    if (!row || row.userId !== userId) return false;
    const updated = { ...row, ...patch, updatedAt: "2026-10-10T00:01:00.000Z" };
    if (patch.resetMastery) {
      updated.masteryScore = 0;
      updated.masteryLevel = "NEW";
      updated.nextReviewAt = null;
    }
    delete updated.resetMastery;
    this.rows.set(materialId, updated);
    return true;
  }

  async archiveMaterial(userId, materialId, archivedAt) {
    const row = this.rows.get(materialId);
    if (!row || row.userId !== userId) return false;
    this.rows.set(materialId, { ...row, archivedAt });
    return true;
  }

  async deleteMaterial(userId, materialId) {
    const row = this.rows.get(materialId);
    if (!row || row.userId !== userId) return false;
    return this.rows.delete(materialId);
  }

  async findAttempt() { return null; }
  async listAttempts() { return []; }
  async listQuizzes() { return []; }
}

test("creates user-scoped materials with Java-compatible defaults and normalized character count", async () => {
  const repository = new MemoryRecitationRepository();
  const service = new RecitationService(repository);
  const material = await service.createMaterial(7, {
    title: "  静夜思  ",
    content: " 床前 明月光，V2! ",
  });
  assert.equal(material.title, "静夜思");
  assert.equal(material.content, "床前 明月光，V2!");
  assert.equal(material.characterCount, 7);
  assert.equal(material.knowledgeType, "RECITATION");
  assert.equal(material.testMode, "AUDIO_RECITATION");
});

test("chooses the default test mode from a non-recitation knowledge type", async () => {
  const service = new RecitationService(new MemoryRecitationRepository());
  const material = await service.createMaterial(7, {
    title: "比例",
    content: "2:3=4:6",
    knowledgeType: "math",
  });
  assert.equal(material.knowledgeType, "MATH");
  assert.equal(material.testMode, "PRACTICE");
});

test("rejects material text with no letters or digits", async () => {
  const service = new RecitationService(new MemoryRecitationRepository());
  await assert.rejects(
    service.createMaterial(7, { title: "标题", content: " ，。!!! " }),
    (error) => error instanceof RecitationError && error.status === 400,
  );
});

test("content changes reset mastery only when normalized content changes", async () => {
  const repository = new MemoryRecitationRepository();
  const service = new RecitationService(repository);
  const material = await service.createMaterial(7, { title: "标题", content: "床前明月光" });
  const stored = repository.rows.get(material.id);
  stored.masteryScore = 91;
  stored.masteryLevel = "MASTERED";
  stored.reviewCount = 4;
  stored.nextReviewAt = "2026-11-09T00:00:00.000Z";

  const punctuationOnly = await service.updateMaterial(7, material.id, { content: "床前，明月光。" });
  assert.equal(punctuationOnly.masteryLevel, "MASTERED");
  const substantive = await service.updateMaterial(7, material.id, { content: "床前明月" });
  assert.equal(substantive.masteryScore, 0);
  assert.equal(substantive.masteryLevel, "NEW");
  assert.equal(substantive.reviewCount, 4);
  assert.equal(substantive.nextReviewAt, null);
});

test("changing knowledge type also selects its default mode and resets mastery", async () => {
  const repository = new MemoryRecitationRepository();
  const service = new RecitationService(repository);
  const material = await service.createMaterial(7, { title: "标题", content: "定义" });
  const updated = await service.updateMaterial(7, material.id, { knowledgeType: "CONCEPT" });
  assert.equal(updated.knowledgeType, "CONCEPT");
  assert.equal(updated.testMode, "AI_QA");
});

test("defaults invalid archive filters to ACTIVE and caps material lists at 100", async () => {
  const repository = new MemoryRecitationRepository();
  const service = new RecitationService(repository);
  await service.createMaterial(7, { title: "标题", content: "内容" });
  await service.listMaterials(7, { archive: "other", limit: 1000, dueOnly: true });
  assert.deepEqual(repository.calls.at(-1).query, {
    search: "",
    masteryLevel: "",
    dueOnly: true,
    limit: 100,
    archive: "ACTIVE",
  });
});

test("does not disclose materials owned by another user and keeps audio disabled by default", async () => {
  const service = new RecitationService(new MemoryRecitationRepository());
  await assert.rejects(
    service.getMaterial(8, 1),
    (error) => error instanceof RecitationError && error.status === 404,
  );
  await assert.rejects(
    service.createAttempt(7, false),
    (error) => error instanceof RecitationError && error.status === 503,
  );
});
