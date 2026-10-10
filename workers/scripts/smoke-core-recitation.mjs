import assert from "node:assert/strict";
import { randomInt } from "node:crypto";
import { readFile } from "node:fs/promises";
import { spawnSync } from "node:child_process";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

const workersDirectory = resolve(fileURLToPath(new URL("..", import.meta.url)));
const coreDirectory = resolve(workersDirectory, "services/core");
const serviceUrl = process.env.CORE_URL || "http://127.0.0.1:8788";
const userId = randomInt(6_000_000, 7_000_000);

function readVars(text) {
  return Object.fromEntries(text.split(/\r?\n/)
    .filter((line) => line && !line.startsWith("#"))
    .map((line) => {
      const separator = line.indexOf("=");
      return [line.slice(0, separator), JSON.parse(line.slice(separator + 1))];
    }));
}

function runD1(statement) {
  const pnpmEntry = process.env.npm_execpath;
  if (!pnpmEntry) throw new Error("Run this smoke check through `pnpm smoke:core-recitation`.");
  const result = spawnSync(process.execPath, [pnpmEntry, "exec", "wrangler", "d1", "execute",
    "dinqor-core-local", "--local", "--json", "--command", statement,
  ], { cwd: coreDirectory, encoding: "utf8", windowsHide: true });
  if (result.status !== 0) {
    throw new Error(`Local Core D1 smoke query failed (${result.error?.message ?? result.status ?? "spawn"}).`);
  }
  try {
    return JSON.parse(result.stdout);
  } catch {
    throw new Error("Local Core D1 smoke query returned invalid JSON.");
  }
}

const coreVars = readVars(await readFile(resolve(coreDirectory, ".dev.vars"), "utf8"));
const headers = {
  "content-type": "application/json",
  "x-dinqor-service-token": coreVars.INTERNAL_SERVICE_TOKEN,
};
let materialId;

async function post(path, body) {
  const response = await fetch(new URL(path, serviceUrl), {
    method: "POST", headers, body: JSON.stringify(body),
  });
  const value = await response.json();
  return { response, value };
}

try {
  const created = await post("/internal/agent/recitation/material/create", {
    userId,
    draft: { title: "本地测验冒烟", content: "平方根是非负数", knowledgeType: "CONCEPT", testMode: "AI_QA" },
  });
  assert.equal(created.response.status, 200);
  assert.equal(created.value.title, "本地测验冒烟");
  assert.equal(created.value.testMode, "AI_QA");
  assert.deepEqual(Object.keys(created.value).sort(), [
    "content", "id", "knowledgeType", "masteryLevel", "masteryScore", "nextReviewAt",
    "reviewCount", "testMode", "title",
  ].sort());
  materialId = created.value.id;

  const count = await post("/internal/agent/recitation/material/count", {
    userId, filter: { search: "本地测验" },
  });
  assert.equal(count.value.count, 1);
  const foreignRead = await post("/internal/agent/recitation/material/get", { userId: userId + 10, materialId });
  assert.equal(foreignRead.response.status, 404);

  const opened = await post("/internal/agent/recitation/quiz/open", {
    userId, materialId, conversationId: 781,
  });
  assert.equal(opened.response.status, 200);
  assert.equal(opened.value.materialId, materialId);
  assert.equal(opened.value.contentTruncated, false);
  assert.equal(opened.value.recentQuizzes.length, 0);

  const gradeBody = {
    userId,
    quizSessionId: opened.value.quizSessionId,
    conversationId: 781,
    verdict: "两题各有证据",
    items: [
      { question: "平方根的取值范围", userAnswer: "非负数", comment: "正确", credit: 1 },
      { question: "负数的实数平方根", userAnswer: "没有", comment: "部分正确", credit: 0.5 },
    ],
  };
  const first = await post("/internal/agent/recitation/quiz/record", gradeBody);
  assert.equal(first.response.status, 200);
  assert.equal(first.value.score, 75);
  assert.equal(first.value.masteryLevel, "APPLYING");
  assert.equal(first.value.previousMasteryLevel, "NEW");
  assert.equal(first.value.reviewCount, 1);

  const replay = await post("/internal/agent/recitation/quiz/record", gradeBody);
  assert.equal(replay.response.status, 200);
  assert.equal(replay.value.attemptId, first.value.attemptId);
  assert.equal(replay.value.reviewCount, 1);

  const wrongConversation = await post("/internal/agent/recitation/quiz/record", {
    ...gradeBody, conversationId: 782,
  });
  assert.equal(wrongConversation.response.status, 409);

  const history = runD1(`SELECT review_count, mastery_level, (SELECT COUNT(*) FROM knowledge_quiz_attempt WHERE quiz_session_id = ${opened.value.quizSessionId}) AS attempts FROM recitation_material WHERE user_id = ${userId} AND id = ${materialId}`);
  const row = history[0]?.results?.[0];
  assert.equal(row?.review_count, 1);
  assert.equal(row?.mastery_level, "APPLYING");
  assert.equal(row?.attempts, 1);
  console.log("Core Agent recitation contract smoke passed.");
} finally {
  runD1(`DELETE FROM knowledge_quiz_attempt WHERE user_id = ${userId}; DELETE FROM knowledge_quiz_session WHERE user_id = ${userId}; DELETE FROM recitation_material WHERE user_id = ${userId};`);
}
