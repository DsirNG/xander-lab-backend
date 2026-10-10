import assert from "node:assert/strict";
import test, { afterEach, mock } from "node:test";
import { CoreAgentRecitationMapper } from "../.test-build/mappers/core-agent-recitation-mapper.js";
import { AgentRecitationError, AgentRecitationService } from "../.test-build/services/agent-recitation-service.js";

afterEach(() => mock.restoreAll());

test("Core recitation mapper sends owner-scoped calls with the service token", async () => {
  const mapper = new CoreAgentRecitationMapper({
    CORE_SERVICE_URL: "https://core.example",
    CORE_SERVICE_TOKEN: "core-service-secret",
  });
  const calls = [];
  mock.method(globalThis, "fetch", async (request) => {
    const url = new URL(request.url);
    calls.push({ path: url.pathname, token: request.headers.get("x-dinqor-service-token"), body: await request.json() });
    if (url.pathname.endsWith("/search")) return Response.json({ materials: [{
      id: 19, title: "Personal item", content: "My original notes.", knowledgeType: "CONCEPT",
    }] });
    if (url.pathname.endsWith("/count")) return Response.json({ count: 1 });
    if (url.pathname.endsWith("/quiz/open")) return Response.json({
      quizSessionId: 31, materialId: 19, title: "Personal item", knowledgeType: "CONCEPT",
      testMode: "CONCEPT", content: "My original notes.", contentTruncated: false,
    });
    if (url.pathname.endsWith("/quiz/record")) return Response.json({
      attemptId: 44, materialId: 19, title: "Personal item", score: 80,
      questionCount: 1, correctCount: 1, masteryLevel: "APPLYING",
      previousMasteryLevel: "FAMILIAR", reviewCount: 2, nextReviewAt: "2026-10-17T00:00:00.000Z",
    });
    return Response.json({ id: 20, title: "New item", content: "Text", knowledgeType: "CONCEPT" });
  });

  const filter = { search: "JWT", masteryLevel: "", dueOnly: false, limit: 5 };
  const items = await mapper.searchMaterials(42, filter);
  const count = await mapper.countMaterials(42, { search: "JWT", masteryLevel: "", dueOnly: false });
  const created = await mapper.createMaterial(42, {
    title: "New item", content: "Text", knowledgeType: "CONCEPT",
  });
  const quiz = await mapper.openQuiz(42, 19, 7);
  const graded = await mapper.recordQuiz(42, 7, {
    quizSessionId: 31, verdict: "Good", items: [{
      question: "Q", userAnswer: "A", comment: "Correct", credit: 1,
    }],
  });

  assert.equal(items[0].id, 19);
  assert.equal(count, 1);
  assert.equal(created.id, 20);
  assert.equal(quiz.quizSessionId, 31);
  assert.equal(graded.attemptId, 44);
  assert.ok(calls.every((call) => call.token === "core-service-secret"));
  assert.ok(calls.every((call) => call.body.userId === 42));
  assert.equal(calls.find((call) => call.path.endsWith("/quiz/open")).body.conversationId, 7);
  assert.deepEqual(calls.map((call) => call.path), [
    "/internal/agent/recitation/material/search",
    "/internal/agent/recitation/material/count",
    "/internal/agent/recitation/material/create",
    "/internal/agent/recitation/quiz/open",
    "/internal/agent/recitation/quiz/record",
  ]);
});

test("Core recitation mapper refuses cross-account HTTP and failed contracts", async () => {
  const mapper = new CoreAgentRecitationMapper({
    CORE_SERVICE_URL: "http://core.example",
    CORE_SERVICE_TOKEN: "token",
  });
  await assert.rejects(mapper.countMaterials(42, { search: "", masteryLevel: "", dueOnly: false }), /HTTPS/);

  const failing = new CoreAgentRecitationMapper({
    CORE_SERVICE_URL: "https://core.example",
    CORE_SERVICE_TOKEN: "token",
  });
  mock.method(globalThis, "fetch", async () => Response.json({ code: 503 }, { status: 503 }));
  await assert.rejects(failing.countMaterials(42, { search: "", masteryLevel: "", dueOnly: false }), /contract failed/);
});

test("Agent recitation service clamps query output and rejects implicit or invalid writes", async () => {
  const service = new AgentRecitationService({
    async searchMaterials(_userId, filter) {
      assert.equal(filter.limit, 3);
      return Array.from({ length: 3 }, (_, index) => ({
        id: index + 1, title: `Item ${index + 1}`, content: "a".repeat(2500),
        knowledgeType: "CONCEPT", testMode: "CONCEPT", masteryScore: 50,
        masteryLevel: "FAMILIAR", reviewCount: 1, nextReviewAt: null,
      }));
    },
    async countMaterials(_userId, filter) { return filter.masteryLevel === "MASTERED" ? 2 : 7; },
    async createMaterial(_userId, draft) {
      return { id: 8, ...draft, testMode: "CONCEPT", masteryScore: 0, masteryLevel: "NEW",
        reviewCount: 0, nextReviewAt: null };
    },
  });

  const query = await service.queryKnowledge(42, { fullContent: true, limit: 100 });
  assert.equal(query.data.items.length, 3);
  assert.equal(query.data.items[0].contentExcerpt.length, 2001);
  assert.equal(query.data.items[0].truncated, true);
  assert.match(query.answer, /还有 4 条/);
  const created = await service.createKnowledge(42, {
    title: "Saved", content: "source", knowledgeType: "CONCEPT",
  });
  assert.equal(created.data.id, 8);
  await assert.rejects(service.createKnowledge(42, {
    title: "", content: "unrequested", knowledgeType: "CONCEPT",
  }), (error) => error instanceof AgentRecitationError && error.status === 400);
});

test("Agent quiz tools bind sessions to the authenticated conversation and validate grading evidence", async () => {
  const calls = [];
  const service = new AgentRecitationService({
    async searchMaterials() { return []; },
    async countMaterials() { return 0; },
    async createMaterial() { throw new Error("unexpected write"); },
    async openQuiz(userId, materialId, conversationId) {
      calls.push({ method: "open", userId, materialId, conversationId });
      return {
        quizSessionId: 11, materialId, title: "HTTP basics", knowledgeType: "CONCEPT",
        testMode: "CONCEPT", content: "HTTP requests have methods and headers.", contentTruncated: false,
      };
    },
    async recordQuiz(userId, conversationId, input) {
      calls.push({ method: "record", userId, conversationId, input });
      return {
        attemptId: 12, materialId: 7, title: "HTTP basics", score: 75, questionCount: 1,
        correctCount: 0, masteryLevel: "APPLYING", previousMasteryLevel: "FAMILIAR",
        reviewCount: 2, nextReviewAt: "2026-10-17T00:00:00.000Z",
      };
    },
  });

  const quiz = await service.openKnowledgeQuiz(42, 9, { materialId: 7, questionCount: 40 });
  assert.equal(quiz.questionCount, 10);
  assert.equal(quiz.source, "HTTP requests have methods and headers.");
  assert.equal(quiz.data.quizSessionId, 11);
  const graded = await service.recordKnowledgeQuiz(42, 9, {
    quizSessionId: 11, verdict: "Good effort", items: [{
      question: "Name one request method.", userAnswer: "GET", comment: "Correct", credit: 0.75,
    }],
  });
  assert.match(graded.answer, /得分 75 分/);
  assert.deepEqual(calls, [
    { method: "open", userId: 42, materialId: 7, conversationId: 9 },
    { method: "record", userId: 42, conversationId: 9, input: {
      quizSessionId: 11, verdict: "Good effort", items: [{
        question: "Name one request method.", userAnswer: "GET", comment: "Correct", credit: 0.75,
      }],
    } },
  ]);
  await assert.rejects(service.recordKnowledgeQuiz(42, 9, {
    quizSessionId: 11, items: [{ question: "Q", userAnswer: "A", comment: "", credit: 2 }],
  }), (error) => error instanceof AgentRecitationError && error.status === 400);
});
