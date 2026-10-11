import assert from "node:assert/strict";
import test from "node:test";
import { handleBlogMediaRequest } from "../.test-build/controllers/blog-media-controller.js";
import { BlogMediaService } from "../.test-build/services/blog-media-service.js";
import worker from "../.test-build/index.js";

const tinyPng = Uint8Array.from(Buffer.from(
  "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4890000000b4944415408d763f8cfc000000301010018dd8db10000000049454e44ae426082",
  "hex",
));

function setup() {
  const rows = [];
  const objects = new Map();
  let nextId = 1;
  const repository = {
    async listImages(userId, contentType, keyword, limit) {
      return rows.filter((row) => row.userId === userId && (!contentType || row.contentType === contentType)
        && (!keyword || row.originalName.toLowerCase().includes(keyword.toLowerCase())))
        .sort((a, b) => b.createdAt.localeCompare(a.createdAt) || b.id - a.id).slice(0, limit);
    },
    async countAgentImages(userId) { return rows.filter((row) => row.userId === userId && row.sourceType === "agent_generated").length; },
    async listAgentImages(userId, limit, offset) {
      return rows.filter((row) => row.userId === userId && row.sourceType === "agent_generated")
        .sort((a, b) => b.id - a.id).slice(offset, offset + limit);
    },
    async findOwned(userId, id) { return rows.find((row) => row.id === id && row.userId === userId) ?? null; },
    async listByTask(userId, taskId) { return rows.filter((row) => row.userId === userId && row.agentTaskId === taskId); },
    async listRecentGenerated(userId, limit) {
      return rows.filter((row) => row.userId === userId && row.sourceType === "agent_generated")
        .sort((a, b) => b.id - a.id).slice(0, limit);
    },
    async findPublicByStoredName(storedName) { return rows.find((row) => row.storedName === storedName) ?? null; },
    async deleteOwned(userId, id) {
      const index = rows.findIndex((row) => row.id === id && row.userId === userId);
      if (index < 0) return false;
      rows.splice(index, 1);
      return true;
    },
    async insert(input) {
      const row = { ...input, id: nextId++, width: input.width ?? null, height: input.height ?? null,
        agentTaskId: input.agentTaskId ?? null, generationMeta: input.generationMeta ?? null,
        createdAt: new Date(1_800_000_000_000 + nextId).toISOString() };
      rows.push(row);
      return row;
    },
  };
  const uploaded = [];
  const deleted = [];
  const storage = {
    async upload(key, bytes, contentType) {
      uploaded.push({ key, bytes: bytes.byteLength, contentType });
      objects.set(key, new Uint8Array(bytes));
      return { url: `https://cdn.example.test/photos/blog/${key}`, storedName: key };
    },
    async read(key) {
      const bytes = objects.get(key);
      return bytes ? { body: new Response(bytes).body } : null;
    },
    async delete(key) { deleted.push(key); },
  };
  return { rows, repository, storage, uploaded, deleted, service: new BlogMediaService(repository, storage) };
}

test("media upload keeps bytes in OSS and stores owner metadata and PNG dimensions in D1", async () => {
  const { service, rows, uploaded } = setup();
  const saved = await service.uploadImage({ userId: 42, bytes: tinyPng, originalName: "C:\\images\\cover.png", contentType: "image/png" });
  assert.equal(saved.userId, 42);
  assert.equal(saved.originalName, "cover.png");
  assert.equal(saved.width, 1);
  assert.equal(saved.height, 1);
  assert.equal(saved.contentType, "image/png");
  assert.equal(rows.length, 1);
  assert.equal(uploaded.length, 1);
  assert.match(uploaded[0].key, /^42\/\d{4}\/\d{2}\/[0-9a-f-]+\.png$/);
  assert.equal(uploaded[0].bytes, tinyPng.byteLength);
});

test("media upload rejects spoofed MIME types and cleans OSS objects after D1 failure", async () => {
  const { service, repository, uploaded, deleted } = setup();
  await assert.rejects(() => service.uploadImage({ userId: 42, bytes: tinyPng, originalName: "x.gif", contentType: "image/gif" }),
    /有效的 PNG/);
  repository.insert = async () => { throw new Error("D1 write failed"); };
  await assert.rejects(() => service.uploadImage({ userId: 42, bytes: tinyPng, originalName: "x.png", contentType: "image/png" }),
    /D1 write failed/);
  assert.equal(uploaded.length, 1);
  assert.deepEqual(deleted, [uploaded[0].key]);
});

test("media lists and agent-image pagination stay owner-scoped and tolerate bad generation metadata", async () => {
  const { service, rows } = setup();
  await service.uploadImage({ userId: 42, bytes: tinyPng, originalName: "normal.png", contentType: "image/png" });
  const generated = await service.uploadImage({ userId: 42, bytes: tinyPng, originalName: "chapter.png", contentType: "image/png",
    sourceType: "agent_generated", agentTaskId: 90, generationMeta: JSON.stringify({ title: "Chapter", prompt: "forest", model: "image-test", size: "1024x1024" }) });
  await service.uploadImage({ userId: 7, bytes: tinyPng, originalName: "private.gif", contentType: "image/png" });
  rows.push({ ...generated, id: 99, originalName: "broken.png", generationMeta: "{" });

  const page = await service.agentImages(42, 1, 1);
  assert.equal(page.total, 2);
  assert.equal(page.records.length, 1);
  assert.equal(page.records[0].title, "broken.png");
  assert.equal(page.records[0].prompt, null);
  const next = await service.agentImages(42, 2, 1);
  assert.equal(next.records[0].title, "Chapter");
  assert.equal(next.hasMore, false);
  assert.equal((await service.images(42, "gif", null)).length, 0);
  assert.equal((await service.images(42, "recent", "normal")).length, 1);
  assert.equal((await service.findOwned(7, generated.id)), null);
  assert.equal((await service.listByTask(7, 90)).length, 0);
  assert.equal((await service.findReusableGenerated(42, "Chapter"))?.id, generated.id);
});

test("private media controller protects owner reads and accepts bounded multipart image uploads", async () => {
  const { service } = setup();
  const anonymous = await handleBlogMediaRequest(new Request("https://content/api/blog/media/images"), service);
  assert.equal(anonymous.status, 401);
  const form = new FormData();
  form.set("file", new File([tinyPng], "tiny.png", { type: "image/png" }));
  const upload = await handleBlogMediaRequest(new Request("https://api.dinqor.cn/api/blog/media/images", {
    method: "POST", headers: { "x-user-id": "42" }, body: form,
  }), service, "https://dinqor-content-automation.dinqor.workers.dev/api/public/blog/media");
  assert.equal(upload.status, 200);
  const payload = await upload.json();
  assert.equal(payload.data.width, 1);
  assert.equal(payload.data.url, `https://dinqor-content-automation.dinqor.workers.dev/api/public/blog/media/${payload.data.storedName}`);
  const list = await handleBlogMediaRequest(new Request("https://api.dinqor.cn/api/blog/media/images?scope=recent", {
    headers: { "x-user-id": "42" },
  }), service, "https://dinqor-content-automation.dinqor.workers.dev/api/public/blog/media");
  const listed = await list.json();
  assert.equal(listed.data[0].id, payload.data.id);
  assert.equal(listed.data[0].url, payload.data.url);

  const internal = await handleBlogMediaRequest(new Request("https://content/internal/content/media/find-owned", {
    method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ userId: 42, id: payload.data.id }),
  }), service);
  assert.equal((await internal.json()).userId, 42);
  const invalidPage = await handleBlogMediaRequest(new Request("https://content/api/blog/media/images/agent?page=word", {
    headers: { "x-user-id": "42" },
  }), service);
  assert.equal(invalidPage.status, 400);
});

test("public media proxy serves only registered images from the private object store", async () => {
  const { service } = setup();
  const asset = await service.uploadImage({ userId: 42, bytes: tinyPng, originalName: "cover.png", contentType: "image/png" });
  const response = await handleBlogMediaRequest(new Request(`https://content/api/public/blog/media/${asset.storedName}`), service);
  assert.equal(response.status, 200);
  assert.equal(response.headers.get("content-type"), "image/png");
  assert.equal(response.headers.get("access-control-allow-origin"), "*");
  assert.equal(response.headers.get("x-content-type-options"), "nosniff");
  assert.deepEqual(new Uint8Array(await response.arrayBuffer()), tinyPng);

  const missing = await handleBlogMediaRequest(new Request("https://content/api/public/blog/media/42/2026/10/00000000-0000-0000-0000-000000000000.png"), service);
  assert.equal(missing.status, 404);
  const traversal = await handleBlogMediaRequest(new Request("https://content/api/public/blog/media/..%2Fsecrets.txt"), service);
  assert.equal(traversal.status, 404);
});

test("public media URLs work without the internal service token while other Content routes remain protected", async () => {
  const previousFetch = globalThis.fetch;
  const storedName = "42/2026/10/00000000-0000-0000-0000-000000000001.png";
  const row = {
    id: 1, userId: 42, url: "https://food-cinder.oss-cn-beijing.aliyuncs.com/old.png",
    originalName: "photo.png", storedName, size: tinyPng.byteLength, contentType: "image/png",
    width: 1, height: 1, sourceType: "user_upload", agentTaskId: null, generationMeta: null,
    createdAt: "2026-10-11T00:00:00.000Z",
  };
  const statement = { bind() { return this; }, async first() { return row; } };
  globalThis.fetch = async (input, init) => {
    assert.equal(new URL(input).hostname, "dinqor.oss-cn-beijing.aliyuncs.com");
    assert.equal(init.method, "GET");
    return new Response(tinyPng);
  };
  try {
    const environment = {
      DB: { prepare() { return statement; }, async batch() { return []; } },
      ALIYUN_OSS_ENDPOINT: "oss-cn-beijing.aliyuncs.com",
      ALIYUN_OSS_BUCKET: "dinqor",
      ALIYUN_OSS_ACCESS_KEY_ID: "test-access-key",
      ALIYUN_OSS_ACCESS_KEY_SECRET: "test-access-secret",
    };
    const response = await worker.fetch(
      new Request(`https://content/api/public/blog/media/${storedName}`),
      environment,
      { waitUntil() {} },
    );
    assert.equal(response.status, 200);
    assert.equal(response.headers.get("access-control-allow-origin"), "*");
    assert.deepEqual(new Uint8Array(await response.arrayBuffer()), tinyPng);

    const protectedResponse = await worker.fetch(
      new Request("https://content/api/blog/posts"), environment, { waitUntil() {} },
    );
    assert.equal(protectedResponse.status, 401);
  } finally {
    globalThis.fetch = previousFetch;
  }
});

test("internal Agent image registration stores bytes in Content OSS and compensating delete cleans the object", async () => {
  const { service, rows, uploaded, deleted } = setup();
  const response = await handleBlogMediaRequest(new Request("https://content/internal/content/media/upload-image", {
    method: "POST",
    headers: {
      "content-type": "application/octet-stream",
      "x-dinqor-user-id": "42",
      "x-dinqor-original-name": "screen%20shot.png",
      "x-dinqor-content-type": "image/png",
    },
    body: tinyPng,
  }), service);
  assert.equal(response.status, 200);
  const asset = await response.json();
  assert.equal(asset.userId, 42);
  assert.equal(asset.originalName, "screen shot.png");
  assert.equal(asset.sourceType, "user_upload");
  assert.equal(uploaded.length, 1);
  assert.equal(rows.length, 1);
  const cleanup = await handleBlogMediaRequest(new Request("https://content/internal/content/media/delete-owned", {
    method: "POST", headers: { "content-type": "application/json" },
    body: JSON.stringify({ userId: 42, id: asset.id }),
  }), service);
  assert.deepEqual(await cleanup.json(), { deleted: true });
  assert.equal(rows.length, 0);
  assert.deepEqual(deleted, [uploaded[0].key]);
});

test("internal Agent generated-image contract decodes metadata and records the image in the Content gallery", async () => {
  const { service, rows } = setup();
  const generationMeta = JSON.stringify({ title: "D1 图解", prompt: "用简洁图示解释 D1" });
  const response = await handleBlogMediaRequest(new Request("https://content/internal/content/media/record-generated", {
    method: "POST",
    headers: {
      "content-type": "application/octet-stream",
      "x-dinqor-user-id": "42",
      "x-dinqor-original-name": encodeURIComponent("agent-image.png"),
      "x-dinqor-content-type": "image/png",
      "x-dinqor-generation-meta": encodeURIComponent(generationMeta),
    },
    body: tinyPng,
  }), service, "https://dinqor-content-automation.dinqor.workers.dev/api/public/blog/media");
  assert.equal(response.status, 200);
  const asset = await response.json();
  assert.equal(asset.sourceType, "agent_generated");
  assert.equal(asset.url, `https://dinqor-content-automation.dinqor.workers.dev/api/public/blog/media/${asset.storedName}`);
  assert.deepEqual(JSON.parse(asset.generationMeta), JSON.parse(generationMeta));
  assert.equal(rows.length, 1);
  const page = await handleBlogMediaRequest(new Request("https://content/api/blog/media/images/agent?page=1&size=24", {
    headers: { "x-user-id": "42" },
  }), service, "https://dinqor-content-automation.dinqor.workers.dev/api/public/blog/media");
  const images = await page.json();
  assert.equal(images.data.records[0].url, asset.url);
  assert.equal(Object.hasOwn(images.data.records[0], "storedName"), false);
});
