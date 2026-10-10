import assert from "node:assert/strict";
import test from "node:test";
import { handleBlogManagementRequest } from "../.test-build/controllers/blog-management-controller.js";
import { BlogManagementService } from "../.test-build/services/blog-management-service.js";

function post(overrides = {}) {
  return {
    id: 7, userId: 42, title: "Owned", summary: "summary", contentInline: "original", contentObjectKey: null,
    category: "backend", categoryName: "后端技术", coverImage: null, author: "DinQorAI", date: null,
    publishedAt: null, readTime: "1 min", tips: null, views: 3, status: 0,
    csdnSynced: false, juejinSynced: false, tags: ["workers"],
    ...overrides,
  };
}

function setup(rows = [post()]) {
  const records = new Map(rows.map((row) => [row.id, structuredClone(row)]));
  const objects = new Map();
  const repository = {
    async categoryExists(id) { return id === "backend" || id === "frontend"; },
    async findById(id) { return records.get(id) ?? null; },
    async findMine(userId, id) { const row = records.get(id); return row?.userId === userId ? row : null; },
    async listMine(userId, status, search, page, size) {
      const filtered = [...records.values()].filter((row) => row.userId === userId
        && (status === null ? row.status === 0 || row.status === 1 : row.status === status)
        && (!search || `${row.title} ${row.summary}`.includes(search)));
      const start = (page - 1) * size;
      const pages = filtered.length ? Math.ceil(filtered.length / size) : 0;
      return { records: filtered.slice(start, start + size), total: filtered.length,
        current: page, size, pages, hasMore: page < pages };
    },
    async update(userId, id, patch, tags) {
      const row = records.get(id);
      if (!row || row.userId !== userId) return false;
      Object.assign(row, patch);
      if (tags !== undefined) row.tags = tags;
      return true;
    },
    async updateStatus(userId, id, status, publishedAt) {
      const row = records.get(id);
      if (!row || row.userId !== userId) return false;
      row.status = status;
      if (publishedAt && !row.publishedAt) row.publishedAt = row.date = publishedAt;
      return true;
    },
    async moveToTrash(userId, id) {
      const row = records.get(id);
      if (!row || row.userId !== userId) return false;
      row.status = -1;
      return true;
    },
    async permanentlyDelete(userId, id) {
      const row = records.get(id);
      if (!row || row.userId !== userId || row.status !== -1) return false;
      return records.delete(id);
    },
    async publishedPostId(userId, requestId) { return userId === 42 && requestId === "done" ? 7 : null; },
    async publishRhythm() { return { weekStart: "2026-10-05", dailyAverage: 0, days: [] }; },
    async manageStats() { return { totalArticles: 1, drafts: 1, published: 0, trashed: 0,
      recentViews: 0, recentPublished: 0, averageViews: 0, dailyViews: [] }; },
  };
  const storage = {
    objects,
    async write(key, content) { objects.set(key, content); },
    async read(key) { if (!objects.has(key)) throw new Error("missing OSS object"); return objects.get(key); },
    async delete(key) { objects.delete(key); },
  };
  const service = new BlogManagementService(repository, storage, () => new Date("2026-10-10T10:00:00.000Z"));
  return { records, repository, storage, service };
}

function request(path, { method = "GET", userId = "42", body, search = "" } = {}) {
  return new Request(`https://content.test${path}${search}`, {
    method,
    headers: { ...(userId ? { "x-user-id": userId } : {}), ...(body ? { "content-type": "application/json" } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
}

test("private blog reads require the Gateway identity and return owner-scoped page/detail data", async () => {
  const { service } = setup([post(), post({ id: 8, userId: 99, title: "Other" })]);
  const unauthorized = await handleBlogManagementRequest(request("/api/blog/posts/mine", { userId: null }), service);
  assert.equal(unauthorized.status, 401);
  const page = await handleBlogManagementRequest(request("/api/blog/posts/mine", { search: "?page=1&size=5&status=0" }), service);
  assert.equal(page.status, 200);
  assert.deepEqual((await page.json()).data.records.map((item) => item.id), [7]);
  const ownerDetail = await handleBlogManagementRequest(request("/api/blog/posts/mine/8"), service);
  assert.equal(ownerDetail.status, 404);
  const invalidStatus = await handleBlogManagementRequest(request("/api/blog/posts/mine", { search: "?status=3" }), service);
  assert.equal(invalidStatus.status, 400);
});

test("updates move large Markdown to OSS, replace tags, and clean the former object after a successful write", async () => {
  const initial = post({ contentInline: null, contentObjectKey: "posts/42/old.md" });
  const { service, storage, records } = setup([initial]);
  storage.objects.set(initial.contentObjectKey, "old content");
  const largeContent = "正文".repeat(140_000);
  const updated = await service.update(42, 7, { title: "Updated", content: largeContent, tags: ["typescript", " workers ", "typescript"] });
  const row = records.get(7);
  assert.equal(updated.title, "Updated");
  assert.equal(updated.content, largeContent);
  assert.equal(row.contentInline, null);
  assert.match(row.contentObjectKey, /^posts\/42\/[0-9a-f-]+\.md$/);
  assert.equal(storage.objects.get(row.contentObjectKey), largeContent);
  assert.equal(storage.objects.has("posts/42/old.md"), false);
  assert.deepEqual(row.tags, ["typescript", "workers"]);

  await service.update(42, 7, { content: "small replacement" });
  assert.equal(records.get(7).contentInline, "small replacement");
  assert.equal(records.get(7).contentObjectKey, null);
  assert.equal(storage.objects.has(row.contentObjectKey), false);
});

test("status, trash, permanent deletion and private status/stat routes preserve lifecycle rules", async () => {
  const { service, records } = setup();
  await assert.rejects(service.permanentlyDelete(42, 7), (error) => error.status === 400);
  const status = await handleBlogManagementRequest(request("/api/blog/posts/7/status", {
    method: "PATCH", body: { status: 1 },
  }), service);
  assert.equal(status.status, 200);
  assert.equal((await status.json()).data.date, "2026-10-10");
  assert.equal(records.get(7).status, 1);

  const trash = await handleBlogManagementRequest(request("/api/blog/posts/7", { method: "DELETE" }), service);
  assert.equal(trash.status, 200);
  assert.equal(records.get(7).status, -1);

  const restored = await service.updateStatus(42, 7, { status: 0 });
  assert.equal(restored.status, 0);
  await service.moveToTrash(42, 7);
  await service.permanentlyDelete(42, 7);
  assert.equal(records.has(7), false);

  const published = await handleBlogManagementRequest(request("/api/blog/posts/publish-status", { search: "?requestId=done" }), service);
  assert.deepEqual((await published.json()).data, { status: "published", postId: 7 });
  const rhythm = await handleBlogManagementRequest(request("/api/blog/posts/publish-rhythm"), service);
  assert.equal((await rhythm.json()).data.weekStart, "2026-10-05");
  const stats = await handleBlogManagementRequest(request("/api/blog/posts/manage-stats"), service);
  assert.equal((await stats.json()).data.totalArticles, 1);
});

test("mutations reject another user's posts and allow permanent deletion only from trash", async () => {
  const { service } = setup([post({ id: 9, userId: 99 })]);
  await assert.rejects(service.update(42, 9, { title: "stolen" }), (error) => error.status === 403);
  await assert.rejects(service.permanentlyDelete(99, 9), (error) => error.status === 400);
});
