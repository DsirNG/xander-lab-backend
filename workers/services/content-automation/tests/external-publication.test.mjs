import assert from "node:assert/strict";
import test from "node:test";
import contentWorker from "../.test-build/index.js";

const contentToken = "content-publication-test-token";
const platformToken = "platform-publication-test-token";

function environment() {
  const queries = [];
  const state = { publication: null, post: {
    id: 81,
    userId: 42,
    title: "Worker migrations",
    summary: "A migration summary",
    contentInline: null,
    contentObjectKey: "articles/worker-migrations.md",
    category: "backend",
    categoryName: "后端技术",
    coverImage: null,
    tagsRaw: "cloudflare,typescript",
  }, csdnPublishedToday: 0, csdnCooldownUntil: null };
  return {
    queries,
    state,
    INTERNAL_SERVICE_TOKEN: contentToken,
    PLATFORM_INTEGRATION_SERVICE_URL: "https://platform.example.test",
    PLATFORM_INTEGRATION_SERVICE_TOKEN: platformToken,
    CONTENT_OSS_PUBLIC_BASE_URL: "https://oss.example.test/article-content",
    DB: {
      prepare(query) {
        let values = [];
        const sql = query.replace(/\s+/g, " ").trim();
        const statement = {
          bind(...bound) { values = bound; return statement; },
          async first() {
            queries.push({ operation: "first", sql, values });
            if (sql.includes("COUNT(*) AS count")) return { count: state.csdnPublishedToday };
            if (sql.includes("FROM blog_publish_record")) {
              return state.publication?.status === "SUCCEEDED" && state.publication.platform === values[2]
                ? { platform: state.publication.platform, externalId: state.publication.externalId, url: state.publication.url }
                : null;
            }
            if (sql.includes("FROM blog_post p")) return state.post;
            throw new Error(`Unexpected query: ${sql}`);
          },
          async run() {
            queries.push({ operation: "run", sql, values });
            if (sql.startsWith("INSERT INTO csdn_publication_cooldown")) {
              if (state.csdnCooldownUntil && state.csdnCooldownUntil > values[2]) {
                return { success: true, meta: { changes: 0 } };
              }
              state.csdnCooldownUntil = values[1];
              return { success: true, meta: { changes: 1 } };
            }
            if (sql.startsWith("INSERT INTO blog_publish_record")) {
              if (state.publication?.status === "PENDING") return { success: true, meta: { changes: 0 } };
              state.publication = { status: "PENDING", attemptToken: values[3] };
              return { success: true, meta: { changes: 1 } };
            }
            if (sql.startsWith("UPDATE blog_publish_record") && sql.includes("SET status = 'SUCCEEDED'")) {
              if (state.publication?.status !== "PENDING" || state.publication.attemptToken !== values[7]) {
                return { success: true, meta: { changes: 0 } };
              }
              state.publication = { status: "SUCCEEDED", platform: values[6], externalId: values[0], url: values[1] };
              if (values[6] === "csdn") state.csdnPublishedToday++;
              return { success: true, meta: { changes: 1 } };
            }
            if (sql.startsWith("UPDATE blog_publish_record") && sql.includes("SET status = 'FAILED'")) {
              state.publication = { status: "FAILED" };
              return { success: true, meta: { changes: 1 } };
            }
            throw new Error(`Unexpected query: ${sql}`);
          },
        };
        return statement;
      },
    },
  };
}

function publicationRequest(body, token = contentToken, platform = "juejin") {
  return new Request(`https://content.example.test/internal/agent/publications/81/sync/${platform}`, {
    method: "POST",
    headers: { "content-type": "application/json", "x-dinqor-service-token": token },
    body: JSON.stringify(body),
  });
}

test("internal Juejin publication reads owned article content, calls Platform, and is idempotent", async () => {
  const env = environment();
  const originalFetch = globalThis.fetch;
  const requests = [];
  globalThis.fetch = async (input, init = {}) => {
    const url = new URL(input);
    requests.push({ url, init });
    if (url.hostname === "oss.example.test") return new Response("# Article body");
    if (url.hostname === "platform.example.test") {
      return Response.json({ platform: "juejin", externalId: "juejin-901", url: "https://juejin.cn/post/901" });
    }
    throw new Error(`Unexpected host: ${url.hostname}`);
  };

  try {
    const request = () => publicationRequest({ userId: 42, postId: 81 });
    const first = await contentWorker.fetch(request(), env);
    assert.equal(first.status, 200);
    assert.deepEqual(await first.json(), {
      platform: "juejin", externalId: "juejin-901", url: "https://juejin.cn/post/901",
    });
    assert.equal(requests.length, 2);
    assert.equal(requests[0].url.href, "https://oss.example.test/article-content/articles/worker-migrations.md");
    assert.equal(requests[1].url.pathname, "/internal/platform-integration/blog-posts/81/sync/juejin");
    assert.equal(new Headers(requests[1].init.headers).get("x-dinqor-service-token"), platformToken);
    const forwarded = JSON.parse(requests[1].init.body);
    assert.deepEqual(forwarded, {
      userId: 42,
      postId: 81,
      post: {
        id: 81,
        title: "Worker migrations",
        summary: "A migration summary",
        content: "# Article body",
        category: "backend",
        categoryName: "后端技术",
        coverImage: null,
        tags: ["cloudflare", "typescript"],
      },
    });
    assert.equal(env.state.publication.status, "SUCCEEDED");

    const second = await contentWorker.fetch(request(), env);
    assert.equal(second.status, 200);
    assert.deepEqual(await second.json(), {
      platform: "juejin", externalId: "juejin-901", url: "https://juejin.cn/post/901",
    });
    assert.equal(requests.length, 2, "the successful external publication must not be repeated");
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("internal CSDN publication forwards the owned post and stores a successful result", async () => {
  const env = environment();
  const originalFetch = globalThis.fetch;
  const requests = [];
  globalThis.fetch = async (input, init = {}) => {
    const url = new URL(input);
    requests.push({ url, init });
    if (url.hostname === "oss.example.test") return new Response("# Article body");
    if (url.hostname === "platform.example.test") {
      return Response.json({ platform: "csdn", externalId: "csdn-81", url: "https://blog.csdn.net/a/81" });
    }
    throw new Error(`Unexpected host: ${url.hostname}`);
  };
  try {
    const response = await contentWorker.fetch(publicationRequest({ userId: 42, postId: 81 }, contentToken, "csdn"), env);
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), {
      platform: "csdn", externalId: "csdn-81", url: "https://blog.csdn.net/a/81",
    });
    assert.equal(requests[1].url.pathname, "/internal/platform-integration/blog-posts/81/sync/csdn");
    assert.equal(env.state.publication.status, "SUCCEEDED");
    assert.equal(env.state.csdnPublishedToday, 1);
    assert.ok(env.state.csdnCooldownUntil);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("CSDN daily limit and cooldown reject requests before calling Platform", async () => {
  for (const prepare of [
    (state) => { state.csdnPublishedToday = 5; },
    (state) => { state.csdnCooldownUntil = "9999-01-01T00:00:00.000Z"; },
  ]) {
    const env = environment();
    prepare(env.state);
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => { throw new Error("limited CSDN request must not make a network call"); };
    try {
      const response = await contentWorker.fetch(publicationRequest(
        { userId: 42, postId: 81 }, contentToken, "csdn",
      ), env);
      assert.equal(response.status, 429);
      assert.equal(env.state.publication, null);
    } finally {
      globalThis.fetch = originalFetch;
    }
  }
});

test("publication requires the internal service token and validates the owner projection", async () => {
  const env = environment();
  const unauthorized = await contentWorker.fetch(
    publicationRequest({ userId: 42, postId: 81 }, "wrong-token"), env,
  );
  assert.equal(unauthorized.status, 401);
  assert.equal(env.queries.length, 0);

  const malformed = await contentWorker.fetch(
    publicationRequest({ userId: 42, postId: 82 }), env,
  );
  assert.equal(malformed.status, 400);
  assert.equal(env.queries.length, 0);
});

test("publication returns 404 when the post is not owned by the requested user", async () => {
  const env = environment();
  env.state.post = null;
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => { throw new Error("Platform must not be called"); };
  try {
    const result = await contentWorker.fetch(publicationRequest({ userId: 99, postId: 81 }), env);
    assert.equal(result.status, 404);
    assert.equal(env.state.publication, null);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("only one concurrent request can claim a publication attempt", async () => {
  const env = environment();
  const originalFetch = globalThis.fetch;
  let announcePlatformCall;
  let releasePlatformCall;
  const platformCalled = new Promise((resolve) => { announcePlatformCall = resolve; });
  const platformResult = new Promise((resolve) => { releasePlatformCall = resolve; });
  globalThis.fetch = async (input) => {
    const url = new URL(input);
    if (url.hostname === "oss.example.test") return new Response("Article body");
    if (url.hostname === "platform.example.test") {
      announcePlatformCall();
      await platformResult;
      return Response.json({ platform: "juejin", externalId: "juejin-902", url: null });
    }
    throw new Error(`Unexpected host: ${url.hostname}`);
  };
  try {
    const firstPromise = contentWorker.fetch(publicationRequest({ userId: 42, postId: 81 }), env);
    await platformCalled;
    const second = await contentWorker.fetch(publicationRequest({ userId: 42, postId: 81 }), env);
    assert.equal(second.status, 409);
    releasePlatformCall();
    const first = await firstPromise;
    assert.equal(first.status, 200);
  } finally {
    globalThis.fetch = originalFetch;
  }
});
