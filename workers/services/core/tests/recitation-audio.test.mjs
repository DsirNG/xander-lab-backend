import assert from "node:assert/strict";
import test, { afterEach, mock } from "node:test";
import { RecitationAudioService } from "../.test-build/services/recitation-audio-service.js";
import { RecitationAsrWorkerService } from "../.test-build/services/recitation-asr-worker-service.js";
import { handleRecitationRequest } from "../.test-build/controllers/recitation-controller.js";
import { DashScopeRecitationAsrMapper } from "../.test-build/mappers/dashscope-recitation-asr-mapper.js";
import { OssRecitationAudioMapper } from "../.test-build/mappers/oss-recitation-audio-mapper.js";

afterEach(() => mock.restoreAll());

function attempt(id, overrides = {}) {
  return {
    id, userId: 42, materialId: 9, clientRequestId: `request-${id}`, status: "UPLOADING", audioUrl: null,
    providerTaskId: null, providerId: null, model: null, transcript: null, resultJson: null, score: null,
    errorMessage: null, runToken: null, pollAttempts: 0,
    createdAt: "2026-10-10T00:00:00.000Z", updatedAt: "2026-10-10T00:00:00.000Z", ...overrides,
  };
}

function uploadHarness() {
  const rows = new Map();
  const objects = [];
  const deleted = [];
  let id = 1;
  const repository = {
    async findMaterial(userId, materialId) {
      return userId === 42 && [9, 10].includes(materialId)
        ? { id: materialId, userId, title: "课文", content: "你好世界" } : null;
    },
    async findAttemptByRequestId(userId, requestId) {
      return [...rows.values()].find((row) => row.userId === userId && row.clientRequestId === requestId) ?? null;
    },
    async createAttempt(userId, materialId, clientRequestId) {
      const row = attempt(id++, { userId, materialId, clientRequestId });
      rows.set(row.id, row);
      return row;
    },
    async setAttemptAudio(userId, attemptId, objectKey) {
      const row = rows.get(attemptId);
      if (!row || row.userId !== userId || row.status !== "UPLOADING") return false;
      Object.assign(row, { audioUrl: objectKey, status: "PENDING" });
      return true;
    },
    async failAttempt(userId, attemptId, errorMessage) {
      const row = rows.get(attemptId);
      if (!row || row.userId !== userId) return false;
      Object.assign(row, { status: "FAILED", errorMessage });
      return true;
    },
    async findAttempt(userId, attemptId) {
      const row = rows.get(attemptId);
      return row?.userId === userId ? row : null;
    },
  };
  const storage = {
    async write(key, bytes, contentType) { objects.push({ key, bytes: [...bytes], contentType }); },
    async delete(key) { deleted.push(key); },
    async readUrl(key) { return `https://oss.example/${key}?signed=short-lived`; },
  };
  return { repository, storage, rows, objects, deleted };
}

test("recitation upload stores audio in OSS and replays a matching client request id", async () => {
  const harness = uploadHarness();
  const service = new RecitationAudioService(harness.repository, harness.storage, true);
  const file = { name: "practice.m4a", type: "audio/mp4", size: 3, bytes: new Uint8Array([1, 2, 3]) };
  const first = await service.createAttempt(42, 9, file, "mobile-001");
  const replay = await service.createAttempt(42, 9, file, "mobile-001");
  assert.equal(first.replayed, false);
  assert.equal(first.attempt.status, "PENDING");
  assert.equal(first.attempt.audioUrl, harness.objects[0].key);
  assert.equal(replay.replayed, true);
  assert.equal(replay.attempt.id, first.attempt.id);
  assert.equal(harness.objects.length, 1);
  assert.deepEqual(harness.objects[0].bytes, [1, 2, 3]);
});

test("recitation upload rejects invalid audio, cross-material key reuse, and disabled feature", async () => {
  const harness = uploadHarness();
  const service = new RecitationAudioService(harness.repository, harness.storage, true);
  const file = { name: "notes.txt", type: "text/plain", size: 1, bytes: new Uint8Array([1]) };
  await assert.rejects(service.createAttempt(42, 9, file, "request"), /仅支持/);
  const audio = { name: "voice.opus", type: "application/octet-stream", size: 1, bytes: new Uint8Array([1]) };
  await service.createAttempt(42, 9, audio, "same-id");
  await assert.rejects(service.createAttempt(42, 10, audio, "same-id"), /另一条知识/);
  const disabled = new RecitationAudioService(harness.repository, harness.storage, false);
  await assert.rejects(disabled.createAttempt(42, 9, audio, "disabled"), /尚未启用/);
});

test("recitation multipart endpoint authenticates and returns the uploaded attempt view", async () => {
  const form = new FormData();
  form.set("file", new File([new Uint8Array([5, 6])], "voice.wav", { type: "audio/wav" }));
  form.set("clientRequestId", "controller-001");
  const request = new Request("https://core.test/api/recitations/materials/9/attempts", {
    method: "POST", headers: { authorization: "Bearer access" }, body: form,
  });
  const response = await handleRecitationRequest(request, {
    DB: {}, RECITATION_ENABLED: "true", RECITATION_MAX_AUDIO_BYTES: "20971520",
  }, {
    async authenticateAccessToken(token) {
      assert.equal(token, "access");
      return { kind: "authenticated", user: { id: 42 } };
    },
  }, {
    async getAttempt(userId, id) { assert.equal(userId, 42); assert.equal(id, 6); return { id: 6, status: "PENDING" }; },
  }, {
    async createAttempt(userId, materialId, file, requestId) {
      assert.equal(userId, 42);
      assert.equal(materialId, 9);
      assert.equal(file.name, "voice.wav");
      assert.equal(file.type, "audio/wav");
      assert.deepEqual([...file.bytes], [5, 6]);
      assert.equal(requestId, "controller-001");
      return { attempt: { id: 6 }, replayed: false };
    },
  });
  assert.equal(response.status, 200);
  assert.deepEqual((await response.json()).data, { id: 6, status: "PENDING" });
});

test("OSS recitation URLs are private, expiring, and use signed object writes", async () => {
  const storage = new OssRecitationAudioMapper({
    ALIYUN_OSS_ENDPOINT: "https://oss-cn-hangzhou.aliyuncs.com",
    ALIYUN_OSS_BUCKET: "recitation-audio",
    ALIYUN_OSS_REGION: "cn-hangzhou",
    ALIYUN_OSS_ACCESS_KEY_ID: "test-access-key",
    ALIYUN_OSS_ACCESS_KEY_SECRET: "test-access-secret",
  });
  const readUrl = new URL(await storage.readUrl("42/audio.m4a"));
  assert.equal(readUrl.hostname, "recitation-audio.oss-cn-hangzhou.aliyuncs.com");
  assert.equal(readUrl.searchParams.get("x-oss-expires"), "600");
  assert.equal(readUrl.pathname, "/recitations/42/audio.m4a");
});

test("scheduled ASR worker submits, polls, aligns, and atomically advances mastery", async () => {
  const row = attempt(4, { status: "PENDING", audioUrl: "42/audio.m4a" });
  const completion = [];
  const events = [];
  const repository = {
    async recoverStaleAttemptClaims(cutoff) { events.push(["recover", cutoff]); },
    async listAttemptsByStatus(status) { return status === row.status ? [{ ...row }] : []; },
    async claimAttempt(id, expected, next, token, incrementPoll) {
      if (id !== row.id || row.status !== expected) return false;
      Object.assign(row, { status: next, runToken: token, pollAttempts: row.pollAttempts + Number(incrementPoll) });
      return true;
    },
    async markAttemptSubmitted(id, token, providerId, model, taskId) {
      if (row.id !== id || row.runToken !== token) return false;
      Object.assign(row, { status: "PROCESSING", runToken: null, providerId, model, providerTaskId: taskId });
      return true;
    },
    async findAttempt(userId, id) { return row.userId === userId && row.id === id ? { ...row } : null; },
    async releaseAttemptForPoll(id, token) {
      if (row.id !== id || row.runToken !== token) return false;
      Object.assign(row, { status: "PROCESSING", runToken: null });
      return true;
    },
    async failClaimedAttempt(id, status, token, message) {
      if (row.id !== id || row.status !== status || row.runToken !== token) return false;
      Object.assign(row, { status: "FAILED", runToken: null, errorMessage: message });
      return true;
    },
    async findMaterial(userId, id) { return userId === 42 && id === 9 ? { id: 9, userId, content: "你好世界" } : null; },
    async completeAttempt(input) {
      completion.push(input);
      Object.assign(row, { status: "SUCCEEDED", transcript: input.transcript, resultJson: input.resultJson, score: input.score });
      return true;
    },
  };
  const worker = new RecitationAsrWorkerService(repository, {
    async readUrl(key) { assert.equal(key, "42/audio.m4a"); return "https://oss.example/audio?signature=abc"; },
    async write() {}, async delete() {},
  }, {
    async submit(url) {
      assert.equal(url, "https://oss.example/audio?signature=abc");
      return { providerId: 12, model: "paraformer-v2", taskId: "task-4" };
    },
    async poll(providerId, taskId) {
      assert.equal(providerId, 12);
      assert.equal(taskId, "task-4");
      return { state: "SUCCEEDED", transcript: "你好世界", words: [{ text: "你好", beginTime: 0, endTime: 400 }] };
    },
  }, true);

  await worker.process();

  assert.equal(row.status, "SUCCEEDED");
  assert.equal(completion.length, 1);
  const report = JSON.parse(completion[0].resultJson);
  assert.equal(report.score, 100);
  assert.equal(report.correctCount, 4);
  assert.equal(report.words[0].text, "你好");
  assert.equal(completion[0].masteryLevel, "MASTERED");
});

test("DashScope mapper submits async audio tasks and downloads bounded transcript JSON", async () => {
  const mapper = new DashScopeRecitationAsrMapper({
    async candidates() { return [{ providerId: 8, baseUrl: "https://dashscope.aliyuncs.com/api/v1", apiKey: "test", model: "paraformer-v2" }]; },
  });
  const calls = [];
  mock.method(globalThis, "fetch", async (input, init) => {
    const url = new URL(input);
    calls.push({ url, init });
    if (url.pathname.endsWith("/services/audio/asr/transcription")) {
      const body = JSON.parse(init.body);
      assert.deepEqual(body.input.file_urls, ["https://oss.example/audio"]);
      assert.deepEqual(body.parameters.language_hints, ["zh"]);
      assert.equal(new Headers(init.headers).get("x-dashscope-async"), "enable");
      return Response.json({ output: { task_id: "task-8" } });
    }
    if (url.pathname === "/api/v1/tasks/task-8") {
      return Response.json({ output: { task_status: "SUCCEEDED", results: [
        { subtask_status: "SUCCEEDED", transcription_url: "https://result.aliyuncs.com/result.json" },
      ] } });
    }
    return Response.json({ transcripts: [{ text: "你好", sentences: [{ words: [
      { text: "你好", begin_time: 0, end_time: 400 },
    ] }] }] });
  });

  const submitted = await mapper.submit("https://oss.example/audio");
  const polled = await mapper.poll(submitted.providerId, submitted.taskId);
  assert.deepEqual(submitted, { providerId: 8, model: "paraformer-v2", taskId: "task-8" });
  assert.equal(polled.state, "SUCCEEDED");
  assert.equal(polled.transcript, "你好");
  assert.deepEqual(polled.words, [{ text: "你好", beginTime: 0, endTime: 400 }]);
  assert.equal(calls.length, 3);
});
