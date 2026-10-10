import assert from "node:assert/strict";
import test, { afterEach, mock } from "node:test";
import { AgentAttachmentService } from "../.test-build/services/agent-attachment-service.js";
import { handleAgentAttachmentRequest } from "../.test-build/controllers/agent-attachment-controller.js";
import { OssAgentAttachmentStorageMapper } from "../.test-build/mappers/oss-agent-attachment-storage-mapper.js";
import { AgentRunService } from "../.test-build/services/agent-run-service.js";
import { ContentAgentMediaMapper } from "../.test-build/mappers/content-agent-media-mapper.js";

afterEach(() => mock.restoreAll());

function attachmentHarness(mediaAssets) {
  const rows = new Map();
  const written = [];
  const removed = [];
  let nextId = 1;
  const service = new AgentAttachmentService({
    async create(input) {
      const row = { ...input, id: nextId++, createdAt: "2026-10-10T00:00:00.000Z" };
      rows.set(row.id, row);
      return row;
    },
    async findOwned(userId, id) {
      const row = rows.get(id);
      return row?.userId === userId ? row : null;
    },
    async findOwnedMany(userId, ids) {
      return ids.flatMap((id) => {
        const row = rows.get(id);
        return row?.userId === userId ? [row] : [];
      });
    },
  }, {
    async write(key, bytes, type) { written.push({ key, bytes: [...bytes], type }); },
    async delete(key) { removed.push(key); },
    async signedReadUrl(key) { return `https://oss.example/${key}?signed=short-lived`; },
  }, mediaAssets);
  return { service, rows, written, removed };
}

test("Agent uploads bounded allowed files to OSS and returns owner-bound metadata", async () => {
  const { service, rows, written } = attachmentHarness();
  const uploaded = await service.upload(42, {
    name: "screen shot.png", type: "image/png", size: 4, bytes: new Uint8Array([1, 2, 3, 4]),
  });

  assert.equal(uploaded.id, 1);
  assert.equal(uploaded.name, "screen shot.png");
  assert.equal(uploaded.contentType, "image/png");
  assert.match(uploaded.url, /^https:\/\/oss\.example\/42\/[0-9a-f-]+\.png\?signed=short-lived$/);
  assert.equal(rows.get(uploaded.id).userId, 42);
  assert.equal(written[0].type, "image/png");
  assert.deepEqual(written[0].bytes, [1, 2, 3, 4]);
  assert.doesNotMatch(JSON.stringify(rows.get(uploaded.id)), /AQIDBA/);
});

test("Agent image uploads register a Content-owned media asset and persist its owner reference", async () => {
  const calls = [];
  const mediaAssets = {
    async uploadImage(...args) { calls.push(["upload", ...args]); return { id: 73 }; },
    async deleteAsset(...args) { calls.push(["delete", ...args]); },
  };
  const { service, rows } = attachmentHarness(mediaAssets);
  const image = await service.upload(42, {
    name: "cover.png", type: "image/png", size: 4, bytes: new Uint8Array([1, 2, 3, 4]),
  });
  assert.equal(image.mediaAssetId, 73);
  assert.equal(rows.get(image.id).mediaAssetId, 73);
  assert.deepEqual(calls[0], ["upload", 42, "cover.png", "image/png", new Uint8Array([1, 2, 3, 4])]);
});

test("Agent validates attachment type, size and owner before a model turn", async () => {
  const { service } = attachmentHarness();
  await assert.rejects(service.upload(42, {
    name: "malware.exe", type: "application/octet-stream", size: 1, bytes: new Uint8Array([1]),
  }), /暂不支持/);
  await assert.rejects(service.upload(42, {
    name: "big.pdf", type: "application/pdf", size: 20 * 1024 * 1024 + 1,
    bytes: new Uint8Array(20 * 1024 * 1024 + 1),
  }), /不能超过20MB/);
  const owned = await service.upload(42, {
    name: "doc.pdf", type: "application/pdf", size: 2, bytes: new Uint8Array([7, 8]),
  });
  assert.equal((await service.resolveOwned(42, [{ id: owned.id, url: "https://attacker.invalid" }]))[0].url,
    owned.url);
  await assert.rejects(service.resolveOwned(99, [{ id: owned.id }]), /不属于当前用户/);
  await assert.rejects(service.resolveOwned(42, [{ id: "1" }]), /附件信息无效/);
});

test("Agent attachment controller parses one authenticated multipart file", async () => {
  const form = new FormData();
  form.set("file", new File([new Uint8Array([3, 4])], "notes.txt", { type: "text/plain" }));
  const request = new Request("https://agent.test/api/agent/conversations/attachments", {
    method: "POST", body: form,
  });
  const response = await handleAgentAttachmentRequest(request, {
    async upload(userId, file) {
      assert.equal(userId, 42);
      assert.equal(file.name, "notes.txt");
      assert.equal(file.type, "text/plain");
      assert.deepEqual([...file.bytes], [3, 4]);
      return { id: 9, url: "https://oss.example/signed", name: file.name, contentType: file.type, size: file.size };
    },
  }, async () => 42);
  assert.equal(response.status, 200);
  assert.deepEqual((await response.json()).data, {
    id: 9, url: "https://oss.example/signed", name: "notes.txt", contentType: "text/plain", size: 2,
  });
});

test("Agent storage creates an expiring OSS V4 GET URL", async () => {
  const storage = new OssAgentAttachmentStorageMapper({
    ALIYUN_OSS_ENDPOINT: "https://oss-cn-hangzhou.aliyuncs.com",
    ALIYUN_OSS_BUCKET: "dinqor-attachments",
    ALIYUN_OSS_REGION: "cn-hangzhou",
    ALIYUN_OSS_ACCESS_KEY_ID: "test-access-key",
    ALIYUN_OSS_ACCESS_KEY_SECRET: "test-access-secret",
  });
  const url = new URL(await storage.signedReadUrl("42/notes.pdf"));
  assert.equal(url.hostname, "dinqor-attachments.oss-cn-hangzhou.aliyuncs.com");
  assert.equal(url.pathname, "/agent-attachments/42/notes.pdf");
  assert.equal(url.searchParams.get("x-oss-expires"), "600");
  assert.equal(url.searchParams.get("x-oss-signature-version"), "OSS4-HMAC-SHA256");
  assert.match(url.searchParams.get("x-oss-signature") ?? "", /^[a-f0-9]{64}$/);
});

test("Agent media mapper uploads bytes across the Content HTTPS contract and supports compensation", async () => {
  const calls = [];
  mock.method(globalThis, "fetch", async (input, init) => {
    calls.push({ url: new URL(input), init });
    const path = new URL(input).pathname;
    if (path.endsWith("/record-generated")) return Response.json({ id: 92, url: "https://cdn.example/92.png", width: 1, height: 1, contentType: "image/png" });
    if (path.endsWith("/find-owned")) return Response.json({ id: 93, url: "https://cdn.example/93.png", originalName: "asset.png", size: 20, width: 1, height: 1, contentType: "image/png" });
    return Response.json({ id: 91 });
  });
  const mapper = new ContentAgentMediaMapper({
    CONTENT_AUTOMATION_SERVICE_URL: "https://content.example/base",
    CONTENT_AUTOMATION_SERVICE_TOKEN: "service-token",
  });
  const saved = await mapper.uploadImage(42, "cover image.png", "image/png", new Uint8Array([1, 2, 3]));
  assert.deepEqual(saved, { id: 91 });
  assert.equal(calls[0].url.pathname, "/internal/content/media/upload-image");
  assert.equal(calls[0].init.headers["x-dinqor-service-token"], "service-token");
  assert.equal(calls[0].init.headers["x-dinqor-user-id"], "42");
  assert.equal(calls[0].init.headers["x-dinqor-original-name"], "cover%20image.png");
  assert.deepEqual([...new Uint8Array(calls[0].init.body)], [1, 2, 3]);
  await mapper.deleteAsset(42, 91);
  assert.equal(calls[1].url.pathname, "/internal/content/media/delete-owned");
  assert.deepEqual(JSON.parse(calls[1].init.body), { userId: 42, id: 91 });
  const meta = JSON.stringify({ title: "图解", prompt: "生成示意图" });
  const generated = await mapper.recordGenerated(42, "agent-image.png", "image/png", new Uint8Array([4, 5]), meta);
  assert.deepEqual(generated, { id: 92, url: "https://cdn.example/92.png", width: 1, height: 1, contentType: "image/png" });
  assert.equal(calls[2].url.pathname, "/internal/content/media/record-generated");
  assert.equal(calls[2].init.headers["x-dinqor-original-name"], "agent-image.png");
  assert.equal(calls[2].init.headers["x-dinqor-generation-meta"], encodeURIComponent(meta));
  assert.deepEqual([...new Uint8Array(calls[2].init.body)], [4, 5]);
  const ownedAsset = await mapper.findOwned(42, 93);
  assert.equal(calls[3].url.pathname, "/internal/content/media/find-owned");
  assert.deepEqual(JSON.parse(calls[3].init.body), { userId: 42, id: 93 });
  assert.equal(ownedAsset.originalName, "asset.png");
});

function runHarness(apiStyle, attachmentService, historySnapshot) {
  const state = { status: "ready", messages: [] };
  const repository = {
    async findOwnedConversation(userId, conversationId) {
      return userId === 42 && conversationId === 7
        ? { id: 7, userId: 42, status: state.status, runVersion: 1 } : null;
    },
    async claimTurn(_userId, _conversationId, content, attachments) {
      state.status = "running";
      state.messages = [{ id: 1, role: "user", content, attachmentsJson: JSON.stringify(attachments) }];
      return 1;
    },
    async recentMessages() { return state.messages.length ? state.messages : historySnapshot; },
    async appendEvent() { return 1; },
    async finishTurn() { state.status = "ready"; return { answerEventId: 2, completeEventId: 3 }; },
    async failTurn() { state.status = "failed"; return 4; },
    async isCancellationRequested() { return false; },
    async cancelTurn() { return null; },
    async listEventsAfter() { return []; },
  };
  const service = new AgentRunService(repository, {
    async candidates() {
      return { managed: true, endpoints: [{ providerId: 1, baseUrl: "https://models.example/v1",
        apiKey: "test", model: "attachment-model", apiStyle }] };
    },
  }, { async ensureAvailable() { return true; }, async chargeTokens() {} }, undefined, undefined, undefined,
  attachmentService);
  return { service, state };
}

test("Agent sends owned images and files as Responses API content blocks", async () => {
  const { service: attachments } = attachmentHarness();
  const image = await attachments.upload(42, {
    name: "photo.jpg", type: "image/jpeg", size: 1, bytes: new Uint8Array([1]),
  });
  const file = await attachments.upload(42, {
    name: "notes.pdf", type: "application/pdf", size: 1, bytes: new Uint8Array([2]),
  });
  const { service } = runHarness("RESPONSES", attachments, []);
  let requestBody;
  mock.method(globalThis, "fetch", async (_input, init) => {
    requestBody = JSON.parse(init.body);
    return new Response('data: {"type":"response.output_text.delta","delta":"Read"}\n\ndata: [DONE]\n\n',
      { headers: { "content-type": "text/event-stream" } });
  });

  const runVersion = await service.claimTurn(42, 7, "Summarize these", [image, file]);
  await service.executeTurn(42, 7, runVersion, () => {});
  const blocks = requestBody.input.at(-1).content;
  assert.deepEqual(blocks.map((block) => block.type), ["input_text", "input_image", "input_file"]);
  assert.equal(blocks[1].image_url, image.url);
  assert.equal(blocks[2].file_url, file.url);
});

test("Chat Completions sends images and leaves unsupported document blocks out", async () => {
  const { service: attachments } = attachmentHarness();
  const image = await attachments.upload(42, {
    name: "photo.jpg", type: "image/jpeg", size: 1, bytes: new Uint8Array([1]),
  });
  const file = await attachments.upload(42, {
    name: "notes.pdf", type: "application/pdf", size: 1, bytes: new Uint8Array([2]),
  });
  const { service } = runHarness("CHAT_COMPLETIONS", attachments, []);
  let requestBody;
  mock.method(globalThis, "fetch", async (_input, init) => {
    requestBody = JSON.parse(init.body);
    return new Response('data: {"choices":[{"delta":{"content":"Read"}}]}\n\ndata: [DONE]\n\n',
      { headers: { "content-type": "text/event-stream" } });
  });

  const runVersion = await service.claimTurn(42, 7, "Describe", [image, file]);
  await service.executeTurn(42, 7, runVersion, () => {});
  const content = requestBody.messages.at(-1).content;
  assert.equal(content[0].type, "text");
  assert.deepEqual(content.slice(1), [{ type: "image_url", image_url: { url: image.url } }]);
});
