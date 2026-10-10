import assert from "node:assert/strict";
import test from "node:test";
import worker from "../.test-build/index.js";
import { buildPreviewPage } from "../.test-build/page.js";
import { OssPreviewStorageMapper } from "../.test-build/mappers/oss-preview-storage-mapper.js";

function createDatabase() {
  const sessions = new Map();
  const limits = new Map();

  return {
    sessions,
    limits,
    prepare(sql) {
      let values = [];
      const statement = {
        bind(...parameters) {
          values = parameters;
          return statement;
        },
        async first() {
          if (sql.includes("SELECT 1 AS ok")) return { ok: 1 };
          if (sql.includes("RETURNING request_count")) {
            const [fingerprint, windowStartedAt, updatedAt] = values;
            const current = limits.get(fingerprint);
            const requestCount = !current || current.windowStartedAt < windowStartedAt
              ? 1
              : current.requestCount + 1;
            limits.set(fingerprint, {
              windowStartedAt: Math.max(current?.windowStartedAt ?? windowStartedAt, windowStartedAt),
              requestCount,
              updatedAt,
            });
            return { request_count: requestCount };
          }
          if (sql.includes("FROM preview_session")) {
            const [sessionId, now] = values;
            const session = sessions.get(sessionId);
            return session && session.expiresAt > now ? { ...session } : null;
          }
          throw new Error(`Unexpected first() query: ${sql}`);
        },
        async all() {
          if (sql.includes("FROM preview_session") && sql.includes("expires_at <= ?")) {
            const [now] = values;
            return {
              results: [...sessions.values()]
                .filter((session) => session.expiresAt <= now)
                .slice(0, 100)
                .map(({ sessionId, objectKey }) => ({ sessionId, objectKey })),
            };
          }
          throw new Error(`Unexpected all() query: ${sql}`);
        },
        async run() {
          if (sql.includes("INSERT INTO preview_session")) {
            const [sessionId, objectKey, inlineCode, codeSize, contentSha256, createdAt, expiresAt] = values;
            sessions.set(sessionId, {
              sessionId,
              objectKey,
              inlineCode,
              codeSize,
              contentSha256,
              createdAt,
              expiresAt,
            });
            return { success: true, meta: { changes: 1 } };
          }
          if (sql.startsWith("DELETE FROM preview_session")) {
            const [sessionId, now] = values;
            const session = sessions.get(sessionId);
            if (session?.expiresAt <= now) sessions.delete(sessionId);
            return { success: true, meta: { changes: 1 } };
          }
          if (sql.startsWith("DELETE FROM preview_rate_limit")) {
            const [before] = values;
            for (const [fingerprint, limit] of limits) {
              if (limit.windowStartedAt < before) limits.delete(fingerprint);
            }
            return { success: true, meta: { changes: 1 } };
          }
          throw new Error(`Unexpected run() query: ${sql}`);
        },
      };
      return statement;
    },
  };
}

function makeEnvironment(overrides = {}) {
  return {
    DB: createDatabase(),
    INTERNAL_SERVICE_TOKEN: "a".repeat(64),
    BLOG_HTML_PREVIEW_STORAGE_MODE: "local-inline",
    BLOG_HTML_PREVIEW_PAGE_URL_PATTERN: "http://127.0.0.1:8787/blog-html/%s/raw",
    BLOG_HTML_PREVIEW_FRAME_ANCESTORS: "http://localhost:5173",
    BLOG_HTML_PREVIEW_TTL_SECONDS: "1800",
    BLOG_HTML_PREVIEW_MAX_CODE_BYTES: "262144",
    BLOG_HTML_PREVIEW_RATE_LIMIT_MAX_REQUESTS: "10",
    BLOG_HTML_PREVIEW_RATE_LIMIT_WINDOW_SECONDS: "60",
    ...overrides,
  };
}

function postRequest(body, token = "a".repeat(64), clientIp = "203.0.113.5") {
  const headers = { "content-type": "application/json" };
  if (token) headers["x-dinqor-service-token"] = token;
  if (clientIp) headers["x-dinqor-client-ip"] = clientIp;
  return new Request("https://preview.internal/api/blog-html/previews", {
    method: "POST",
    headers,
    body: JSON.stringify(body),
  });
}

test("create stores only session metadata and a raw request renders hardened HTML", async () => {
  const env = makeEnvironment();
  const create = await worker.fetch(postRequest({ code: "<main>预览内容</main>" }), env);

  assert.equal(create.status, 200);
  const result = await create.json();
  assert.equal(result.code, 200);
  assert.match(result.data.id, /^[0-9a-f]{16}$/);
  assert.equal(
    result.data.pageUrl,
    `http://127.0.0.1:8787/blog-html/${result.data.id}/raw`,
  );
  const session = env.DB.sessions.get(result.data.id);
  assert.equal(session.inlineCode, "<main>预览内容</main>");
  assert.equal(session.objectKey, null);
  assert.equal(session.codeSize, new TextEncoder().encode("<main>预览内容</main>").byteLength);

  const raw = await worker.fetch(new Request(
    `https://preview.internal/blog-html/${result.data.id}/raw`,
  ), env);
  assert.equal(raw.status, 200);
  assert.equal(raw.headers.get("content-type"), "text/html; charset=UTF-8");
  assert.equal(raw.headers.get("cache-control"), "no-store");
  assert.equal(raw.headers.get("x-content-type-options"), "nosniff");
  assert.match(raw.headers.get("content-security-policy"), /frame-ancestors http:\/\/localhost:5173/);
  const page = await raw.text();
  assert.match(page, /default-src 'none'/);
  assert.match(page, /__sandboxHeight/);
  assert.match(page, /<main>预览内容<\/main>/);

  const subdomainRaw = await worker.fetch(new Request(
    `https://${result.data.id}.blogHTML.dinqor.cn/raw`,
  ), env);
  assert.equal(subdomainRaw.status, 200);
});

test("creation requires the Gateway service token while sandbox reads stay public", async () => {
  const env = makeEnvironment();
  const denied = await worker.fetch(postRequest({ code: "<p>x</p>" }, null), env);
  assert.equal(denied.status, 401);

  const publicRaw = await worker.fetch(new Request("https://preview.internal/raw"), env);
  assert.equal(publicRaw.status, 400);
});

test("creation validates code size and applies the fixed-window limit by client IP", async () => {
  const env = makeEnvironment({
    BLOG_HTML_PREVIEW_MAX_CODE_BYTES: "4",
    BLOG_HTML_PREVIEW_RATE_LIMIT_MAX_REQUESTS: "2",
  });

  const tooLarge = await worker.fetch(
    postRequest({ code: "12345" }, "a".repeat(64), "203.0.113.6"),
    env,
  );
  assert.equal(tooLarge.status, 413);

  const first = await worker.fetch(postRequest({ code: "1234" }), env);
  const second = await worker.fetch(postRequest({ code: "abcd" }), env);
  const limited = await worker.fetch(postRequest({ code: "wxyz" }), env);
  assert.equal(first.status, 200);
  assert.equal(second.status, 200);
  assert.equal(limited.status, 429);
  assert.equal(env.DB.sessions.size, 2);
});

test("expired and malformed preview ids are rejected", async () => {
  const env = makeEnvironment();
  const malformed = await worker.fetch(new Request(
    "https://preview.internal/blog-html/not-an-id/raw",
  ), env);
  assert.equal(malformed.status, 400);

  const expired = await worker.fetch(new Request(
    "https://preview.internal/blog-html/0123456789abcdef/raw",
  ), env);
  assert.equal(expired.status, 404);
});

test("full-document previews receive CSP and reporter scripts in the head", () => {
  const page = buildPreviewPage("<!doctype html><html><head><title>Demo</title></head><body>x</body></html>");
  assert.match(page, /<head><title>Demo<\/title><meta http-equiv="Content-Security-Policy"/);
  assert.match(page, /<\/script><\/head>/);
  assert.match(page, /<body>x<\/body>/);
});

test("OSS preview objects use signed PUT, GET, and DELETE requests", async () => {
  const originalFetch = globalThis.fetch;
  const code = "<main>OSS preview</main>";
  let stored = null;
  const calls = [];
  globalThis.fetch = async (input, init = {}) => {
    const url = new URL(input);
    const method = init.method ?? "GET";
    const headers = new Headers(init.headers);
    calls.push({ method, url: url.href });
    assert.match(headers.get("authorization") ?? "", /^OSS4-HMAC-SHA256 /);
    assert.equal(url.href, "https://examplebucket.oss-cn-hangzhou.aliyuncs.com/blog-html-preview/0123456789abcdef/page.html");

    if (method === "PUT") {
      assert.equal(headers.get("content-type"), "text/html; charset=utf-8");
      stored = String(init.body);
      return new Response(null, { status: 200 });
    }
    if (method === "GET") return new Response(stored, { status: 200 });
    if (method === "DELETE") {
      stored = null;
      return new Response(null, { status: 204 });
    }
    throw new Error(`Unexpected OSS method: ${method}`);
  };

  try {
    const env = {
      ALIYUN_OSS_ENDPOINT: "oss-cn-hangzhou.aliyuncs.com",
      ALIYUN_OSS_BUCKET: "examplebucket",
      ALIYUN_OSS_ACCESS_KEY_ID: "test-access-key",
      ALIYUN_OSS_ACCESS_KEY_SECRET: "test-access-secret",
      BLOG_HTML_PREVIEW_OSS_PREFIX: "blog-html-preview",
    };
    const storage = new OssPreviewStorageMapper(env);
    await storage.put("0123456789abcdef/page.html", code);
    assert.equal(await storage.read("0123456789abcdef/page.html", 128), code);
    await storage.delete("0123456789abcdef/page.html");
    assert.deepEqual(calls.map((call) => call.method), ["PUT", "GET", "DELETE"]);
    assert.equal(stored, null);
  } finally {
    globalThis.fetch = originalFetch;
  }
});
