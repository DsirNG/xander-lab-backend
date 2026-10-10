import assert from "node:assert/strict";
import test from "node:test";
import { AgentRecitationError, AgentRecitationService } from "../.test-build/services/agent-recitation-service.js";
import { RecitationService } from "../.test-build/services/recitation-service.js";

class MemoryRecitationRepository {
  rows = new Map();
  nextId = 1;

  async createMaterial(input) {
    const now = new Date().toISOString();
    const row = {
      ...input, id: this.nextId++, masteryScore: 0, masteryLevel: "NEW", reviewCount: 0,
      lastReviewAt: null, nextReviewAt: null, archivedAt: null, createdAt: now, updatedAt: now,
    };
    this.rows.set(row.id, row);
    return row;
  }

  async findMaterial(userId, materialId) {
    const row = this.rows.get(materialId);
    return row?.userId === userId ? { ...row } : null;
  }

  async searchMaterials(userId, query) {
    return [...this.rows.values()].filter((row) => row.userId === userId && !row.archivedAt
      && (!query.search || row.title.includes(query.search) || row.content.includes(query.search))
      && (!query.masteryLevel || row.masteryLevel.toUpperCase() === query.masteryLevel.toUpperCase())
      && (!query.dueOnly || (row.nextReviewAt && row.nextReviewAt <= new Date().toISOString())))
      .slice(0, query.limit).map((row) => ({ ...row }));
  }

  async countMaterials(userId, query) {
    return (await this.searchMaterials(userId, { ...query, limit: Number.MAX_SAFE_INTEGER })).length;
  }

  async updateMaterial() { return false; }
  async archiveMaterial() { return false; }
  async deleteMaterial() { return false; }
  async findAttempt() { return null; }
  async listAttempts() { return []; }
  async listQuizzes() { return []; }
}

class MemoryAgentQuizRepository {
  sessions = new Map();
  attempts = new Map();
  nextSessionId = 1;
  nextAttemptId = 1;

  constructor(privateMaterials) {
    this.materials = privateMaterials;
  }

  materials;

  async createQuizSession(input) {
    const row = { id: this.nextSessionId++, ...input, status: "OPEN", attemptId: null, gradedAt: null };
    this.sessions.set(row.id, row);
    return { ...row };
  }

  async findQuizSession(userId, id) {
    const row = this.sessions.get(id);
    return row?.userId === userId ? { ...row } : null;
  }

  async listRecentQuizAttempts(userId, materialId, limit) {
    return [...this.attempts.values()].filter((row) => row.userId === userId && row.materialId === materialId)
      .sort((a, b) => b.id - a.id).slice(0, limit).map((row) => ({ ...row }));
  }

  async recordQuiz(input) {
    const session = this.sessions.get(input.quizSessionId);
    if (!session || session.userId !== input.userId) return { kind: "not_found" };
    if (session.conversationId !== null && session.conversationId !== input.conversationId) {
      return { kind: "conversation_mismatch" };
    }
    if (session.status === "OPEN" && Date.parse(session.expiresAt) <= Date.parse(input.reviewedAt)) {
      return { kind: "expired" };
    }
    const material = this.materials.rows.get(session.materialId);
    if (!material) return { kind: "not_found" };
    if (session.status === "OPEN") {
      const previousMasteryLevel = material.masteryLevel;
      const attempt = {
        id: this.nextAttemptId++, userId: input.userId, materialId: session.materialId,
        quizSessionId: session.id, conversationId: input.conversationId,
        testMode: material.testMode, questionCount: input.questionCount, correctCount: input.correctCount,
        score: input.score, verdict: input.verdict, itemsJson: input.itemsJson, createdAt: input.reviewedAt,
      };
      this.attempts.set(attempt.id, attempt);
      session.status = "GRADED";
      session.attemptId = attempt.id;
      session.gradedAt = input.reviewedAt;
      material.masteryScore = input.score;
      material.masteryLevel = input.masteryLevel;
      material.reviewCount += 1;
      material.lastReviewAt = input.reviewedAt;
      material.nextReviewAt = input.nextReviewAt;
      return {
        kind: "recorded", firstWrite: true, previousMasteryLevel,
        session: { ...session }, attempt: { ...attempt }, material: { ...material },
      };
    }
    if (!session.attemptId) return { kind: "in_progress" };
    return {
      kind: "recorded", firstWrite: false, previousMasteryLevel: material.masteryLevel,
      session: { ...session }, attempt: { ...this.attempts.get(session.attemptId) }, material: { ...material },
    };
  }
}

function setup() {
  const materials = new MemoryRecitationRepository();
  const recitation = new RecitationService(materials);
  const quizzes = new MemoryAgentQuizRepository(materials);
  return { materials, quizzes, service: new AgentRecitationService(materials, quizzes, recitation) };
}

test("Agent material contract reuses Core validation and exposes only the owner's active matches", async () => {
  const { service } = setup();
  const created = await service.createMaterial(41, { title: "三角公式", content: "sin(a+b)" });
  assert.equal(created.knowledgeType, "RECITATION");
  assert.equal(created.testMode, "AUDIO_RECITATION");
  assert.equal(await service.countMaterials(41, { search: "公式" }), 1);
  assert.equal(await service.countMaterials(42, {}), 0);
  await assert.rejects(service.getMaterial(42, created.id),
    (error) => error instanceof AgentRecitationError && error.status === 404);
});

test("open quiz returns a durable conversation-bound session, a bounded prompt, and recent evidence", async () => {
  const { service } = setup();
  const created = await service.createMaterial(41, { title: "原文", content: "定理".repeat(2500), knowledgeType: "CONCEPT" });
  const opened = await service.openQuiz(41, created.id, 88);
  assert.equal(opened.quizSessionId, 1);
  assert.equal(opened.conversationId, undefined);
  assert.equal(opened.contentTruncated, true);
  assert.equal(opened.content.length, 4001);
  assert.equal(opened.contentLength, 5000);
  assert.equal(opened.recentQuizzes.length, 0);
});

test("quiz grading uses per-question evidence, advances mastery once, and replays the durable result", async () => {
  const { service } = setup();
  const created = await service.createMaterial(41, { title: "平方根", content: "根号下的平方" });
  const opened = await service.openQuiz(41, created.id, 88);
  const items = [
    { question: "平方根是什么", userAnswer: "非负数", comment: "正确", credit: 1 },
    { question: "负数有实数平方根吗", userAnswer: "没有", comment: "正确", credit: 0.5 },
  ];
  const first = await service.recordQuiz(41, opened.quizSessionId, 88, "掌握得不错", items);
  const replay = await service.recordQuiz(41, opened.quizSessionId, 88, "重复提交", items);
  assert.equal(first.score, 75);
  assert.equal(first.correctCount, 1);
  assert.equal(first.masteryLevel, "APPLYING");
  assert.equal(first.previousMasteryLevel, "NEW");
  assert.equal(first.reviewCount, 1);
  assert.equal(replay.attemptId, first.attemptId);
  assert.equal(replay.reviewCount, 1);
  assert.equal(replay.previousMasteryLevel, "APPLYING");
});

test("record quiz rejects missing per-question credits and cross-conversation scoring", async () => {
  const { service } = setup();
  const created = await service.createMaterial(41, { title: "测验", content: "知识内容" });
  const opened = await service.openQuiz(41, created.id, 88);
  await assert.rejects(service.recordQuiz(41, opened.quizSessionId, 88, "", [{ question: "问题" }]),
    (error) => error instanceof AgentRecitationError && error.status === 400 && /credit/.test(error.message));
  await assert.rejects(service.recordQuiz(41, opened.quizSessionId, 89, "", [{ question: "问题", credit: 1 }]),
    (error) => error instanceof AgentRecitationError && error.status === 409);
});
