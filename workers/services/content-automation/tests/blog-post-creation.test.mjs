import assert from "node:assert/strict";
import test from "node:test";
import { handleBlogPostCreationRequest } from "../.test-build/controllers/blog-post-creation-controller.js";
import { BlogPostCreationService } from "../.test-build/services/blog-post-creation-service.js";

function creationRepository() {
  const posts = new Map();
  let nextId = 100;
  return {
    posts,
    createCalls: 0,
    async categoryExists(categoryId) { return categoryId === "backend"; },
    async findCreateReceipt(userId, key) { return posts.get(`${userId}:${key}`) ?? null; },
    async createPost(input) {
      this.createCalls++;
      const key = `${input.userId}:${input.idempotencyKey}`;
      if (!posts.has(key)) {
        posts.set(key, {
          id: nextId++, userId: input.userId, title: input.title, summary: input.summary,
          category: input.categoryId, categoryName: "后端技术", coverImage: input.coverImage,
          author: "DinQorAI", date: input.publishedAt, readTime: input.readTime,
          tips: null, views: 0, status: input.publish ? 1 : 0,
          csdnSynced: 0, juejinSynced: 0, tags: input.tags,
          contentInline: input.contentInline, contentObjectKey: input.contentObjectKey,
          requestHash: input.requestHash,
        });
      }
      return posts.get(key);
    },
  };
}

function contentStorage() {
  const objects = new Map();
  return {
    objects,
    async write(key, content) { objects.set(key, content); },
    async read(key) {
      if (!objects.has(key)) throw new Error("object is missing");
      return objects.get(key);
    },
    async delete(key) { objects.delete(key); },
  };
}

function postInput(overrides = {}) {
  return {
    userId: 42,
    idempotencyKey: "create-article-42",
    title: "  Worker article  ",
    summary: "",
    content: "# Content Automation",
    categoryId: "backend",
    tags: ["cloudflare", "typescript"],
    publish: false,
    ...overrides,
  };
}

test("post creation stores small Markdown inline and replays the same idempotency key", async () => {
  const repository = creationRepository();
  const storage = contentStorage();
  const service = new BlogPostCreationService(repository, storage, () => new Date("2026-10-10T10:00:00.000Z"));
  const first = await service.create(postInput());
  const retry = await service.create(postInput());
  assert.equal(first.id, retry.id);
  assert.equal(first.title, "Worker article");
  assert.equal(first.summary, "# Content Automation");
  assert.equal(first.content, "# Content Automation");
  assert.equal(first.status, 0);
  assert.equal(first.date, null);
  assert.equal(repository.createCalls, 1);
  assert.equal(storage.objects.size, 0);
});

test("post creation stores large Markdown in OSS and rejects key reuse with changed content", async () => {
  const repository = creationRepository();
  const storage = contentStorage();
  const service = new BlogPostCreationService(repository, storage, () => new Date("2026-10-10T10:00:00.000Z"));
  const content = "x".repeat(256 * 1024 + 1);
  const first = await service.create(postInput({ content, publish: true }));
  const row = [...repository.posts.values()][0];
  assert.equal(row.contentInline, null);
  assert.match(row.contentObjectKey, /^posts\/42\/[0-9a-f-]+\.md$/);
  assert.equal(storage.objects.get(row.contentObjectKey), content);
  assert.equal(first.content.length, content.length);
  assert.equal(first.status, 1);
  assert.equal(first.date, "2026-10-10");
  await assert.rejects(service.create(postInput({ content: `${content}changed`, publish: true })),
    (error) => error.status === 409);
  assert.equal(repository.createCalls, 1);
  assert.equal(storage.objects.size, 1);
});

test("post creation removes an uploaded OSS object when D1 creation fails", async () => {
  const repository = creationRepository();
  repository.createPost = async () => { throw new Error("D1 unavailable"); };
  const storage = contentStorage();
  const service = new BlogPostCreationService(repository, storage);
  await assert.rejects(service.create(postInput({ content: "x".repeat(256 * 1024 + 1) })), /D1 unavailable/);
  assert.equal(storage.objects.size, 0);
});

test("post creation controller requires the Gateway identity and preserves the response envelope", async () => {
  const calls = [];
  const service = { async create(input) { calls.push(input); return { id: 100, title: input.title }; } };
  const path = "https://content.test/api/blog/posts";
  const missingIdentity = await handleBlogPostCreationRequest(new Request(path, {
    method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ title: "x" }),
  }), service);
  assert.equal(missingIdentity.status, 401);

  const response = await handleBlogPostCreationRequest(new Request(path, {
    method: "POST",
    headers: { "content-type": "application/json", "x-user-id": "42", "idempotency-key": "key-42" },
    body: JSON.stringify({ title: "Title", content: "Body", categoryId: "backend", publish: false }),
  }), service);
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { code: 200, message: "success", data: { id: 100, title: "Title" } });
  assert.equal(calls[0].userId, 42);
  assert.equal(calls[0].idempotencyKey, "key-42");
  assert.equal(calls[0].publish, false);
});
