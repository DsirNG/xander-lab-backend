import assert from "node:assert/strict";
import test from "node:test";
import contentWorker from "../.test-build/index.js";

const token = "blog-public-test-token";

function postRow(overrides = {}) {
  return {
    id: 71,
    title: "Public article",
    summary: "Public summary",
    category: "backend",
    categoryName: "后端技术",
    coverImage: null,
    userId: 42,
    author: "DinQorAI",
    date: "2026-10-10",
    readTime: "5 min",
    tips: null,
    views: 0,
    status: 1,
    csdnSynced: 0,
    juejinSynced: 1,
    tagsRaw: "cloudflare,typescript",
    contentInline: "Inline article body",
    contentObjectKey: null,
    ...overrides,
  };
}

function environment() {
  const state = {
    post: postRow(),
    viewDedup: new Map(),
    viewEvents: new Set(),
    batches: 0,
    views: 0,
  };
  const db = {
    prepare(query) {
      let values = [];
      const sql = query.replace(/\s+/g, " ").trim();
      const statement = {
        bind(...bound) { values = bound; return statement; },
        get values() { return values; },
        async first() {
          if (sql.includes("COUNT(*) AS total FROM blog_post")) return { total: 2 };
          if (sql.includes("FROM blog_post p")) return state.post;
          if (sql.includes("COUNT(*) AS count FROM blog_post")) return { count: 3 };
          throw new Error(`Unexpected first query: ${sql}`);
        },
        async all() {
          if (sql.includes("FROM blog_post p")) return { results: [state.post] };
          if (sql.includes("FROM blog_category")) return { results: [{ id: "backend", name: "后端技术", count: 1 }] };
          if (sql.includes("FROM blog_tag")) return { results: [{ name: "cloudflare", count: 1 }] };
          throw new Error(`Unexpected all query: ${sql}`);
        },
        async run() { return { success: true, meta: { changes: 0 } }; },
      };
      return statement;
    },
    async batch(statements) {
      state.batches += 1;
      const [deleteDedup, createDedup, createView, increment] = statements;
      const [dedupeKey, now] = deleteDedup.values;
      const current = state.viewDedup.get(dedupeKey);
      if (current && current.expiresAt <= now) state.viewDedup.delete(dedupeKey);

      const [newKey, eventId, expiresAt] = createDedup.values;
      if (!state.viewDedup.has(newKey)) state.viewDedup.set(newKey, { eventId, expiresAt });

      const [viewEventId, postId] = createView.values;
      if (state.viewDedup.get(newKey)?.eventId === viewEventId) state.viewEvents.add(viewEventId);

      const [updatedAt, updatedPostId, incrementEventId] = increment.values;
      void updatedAt;
      if (updatedPostId === state.post.id && state.viewEvents.has(incrementEventId)) {
        state.views += 1;
        state.post.views = state.views;
        return [{ success: true, meta: { changes: 0 } }, { success: true, meta: { changes: 0 } },
          { success: true, meta: { changes: 1 } }, { success: true, meta: { changes: 1 } }];
      }
      return [{ success: true, meta: { changes: 0 } }, { success: true, meta: { changes: 0 } },
        { success: true, meta: { changes: 0 } }, { success: true, meta: { changes: 0 } }];
    },
  };
  return { state, INTERNAL_SERVICE_TOKEN: token, DB: db };
}

function request(path, options = {}) {
  return new Request(`https://content.test${path}`, {
    ...options,
    headers: {
      "x-dinqor-service-token": token,
      ...(options.headers ?? {}),
    },
  });
}

test("public blog list, detail, and taxonomy keep the response envelope and fields", async () => {
  const env = environment();
  const listed = await contentWorker.fetch(request("/api/blog/posts?page=0&size=100&category=backend"), env);
  assert.equal(listed.status, 200);
  const listBody = await listed.json();
  assert.equal(listBody.code, 200);
  assert.equal(listBody.data.current, 1);
  assert.equal(listBody.data.size, 50);
  assert.equal(listBody.data.records[0].content, null);
  assert.deepEqual(listBody.data.records[0].tags, ["cloudflare", "typescript"]);
  assert.equal(listBody.data.records[0].juejinSynced, true);

  const detail = await contentWorker.fetch(request("/api/blog/posts/71"), env);
  assert.equal(detail.status, 200);
  assert.equal((await detail.json()).data.content, "Inline article body");

  const categories = await contentWorker.fetch(request("/api/blog/categories"), env);
  assert.equal((await categories.json()).data[0].id, "backend");
  const popularTags = await contentWorker.fetch(request("/api/blog/tags/popular?limit=100"), env);
  assert.equal((await popularTags.json()).data[0].name, "cloudflare");
});

test("public article views count once per edge IP during the 24-hour dedupe window", async () => {
  const env = environment();
  const viewRequest = () => request("/api/blog/posts/71/view", {
    method: "POST",
    headers: { "x-dinqor-client-ip": "203.0.113.42", "user-agent": "smoke-agent" },
  });
  const first = await contentWorker.fetch(viewRequest(), env);
  assert.deepEqual((await first.json()).data, { counted: true });
  const second = await contentWorker.fetch(viewRequest(), env);
  assert.deepEqual((await second.json()).data, { counted: false });
  assert.equal(env.state.views, 1);
  assert.equal(env.state.batches, 2);
});

test("managed blog count validates its owner projection and returns the legacy shape", async () => {
  const env = environment();
  const result = await contentWorker.fetch(request("/internal/content/profile/managed-count", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ userId: 42 }),
  }), env);
  assert.deepEqual(await result.json(), { count: 3 });
});
