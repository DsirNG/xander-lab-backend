import assert from "node:assert/strict";
import test from "node:test";
import integrationWorker from "../.test-build/index.js";
import { handleCsdnPublicationRequest } from "../.test-build/controllers/csdn-publication-controller.js";
import { CsdnProviderError } from "../.test-build/interfaces/csdn-publication-port.js";
import { CsdnPublicationMapper } from "../.test-build/mappers/csdn-publication-mapper.js";
import { CsdnPublicationService } from "../.test-build/services/csdn-publication-service.js";
import { PlatformCredentialError } from "../.test-build/services/platform-credential-service.js";

const token = "csdn-publication-test-token";
const encryptionKey = Buffer.alloc(32, 9).toString("base64");

function input(overrides = {}) {
  return {
    userId: 42,
    postId: 301,
    title: "Worker architecture",
    summary: "A practical summary",
    content: "# Introduction\n\n![diagram](https://images.example.test/diagram.png)",
    category: "后端技术",
    coverImage: null,
    tags: ["cloudflare", "typescript"],
    ...overrides,
  };
}

function d1Fixture() {
  let credential = null;
  return {
    get credential() { return credential; },
    prepare(sql) {
      let values = [];
      const query = {
        bind(...args) { values = args; return query; },
        async first() {
          if (sql.includes("FROM platform_credential")) return credential ? { ...credential } : null;
          throw new Error(`unexpected SQL: ${sql}`);
        },
        async run() {
          if (sql.includes("INSERT INTO platform_credential")) {
            credential = {
              userId: values[0], platform: values[1], encryptedCredential: values[2],
              accountName: values[3], status: "ACTIVE",
            };
            return { success: true, meta: { changes: 1 } };
          }
          if (sql.includes("UPDATE platform_credential")) {
            if (credential?.status === "ACTIVE") credential.status = "EXPIRED";
            return { success: true, meta: { changes: credential ? 1 : 0 } };
          }
          throw new Error(`unexpected SQL: ${sql}`);
        },
      };
      return query;
    },
  };
}

test("CSDN mapper uploads Markdown images and signs the legacy save request", async () => {
  const previousFetch = globalThis.fetch;
  const calls = [];
  globalThis.fetch = async (url, init) => {
    calls.push({ url: new URL(url), init, body: JSON.parse(init.body) });
    return calls.length === 1
      ? Response.json({ code: 200, data: "https://img.csdn.net/hosted/diagram.png" })
      : new Response('{"code":200,"data":{"article_id":1234567890123456789,"url":"https://blog.csdn.net/alice/article/details/301"}}');
  };
  try {
    const mapper = new CsdnPublicationMapper({
      PLATFORM_CSDN_API_KEY: "api-key",
      PLATFORM_CSDN_WEB_CONSOLE_SECRET: "web-secret",
    });
    const result = await mapper.publish({ cookie: "CSDN=private-cookie" }, input());
    assert.deepEqual(result, {
      externalId: "1234567890123456789",
      url: "https://blog.csdn.net/alice/article/details/301",
    });
    assert.equal(calls.length, 2);
    assert.equal(calls[0].url.origin, "https://bizapi.csdn.net");
    assert.equal(calls[0].url.pathname, "/resource-api/v1/image/external/storage");
    assert.equal(calls[0].body.art_id, "-301");
    assert.equal(calls[0].init.headers["x-ca-signature-headers"], "x-ca-key,x-ca-nonce,x-ca-timestamp");
    assert.ok(calls[0].init.headers["x-ca-signature"]);
    assert.equal(calls[1].url.pathname, "/blog-console-api/v1/postedit/saveArticle");
    assert.equal(calls[1].init.headers.cookie, "CSDN=private-cookie");
    assert.equal(calls[1].init.headers["x-ca-signature-headers"], "x-ca-key,x-ca-nonce");
    assert.equal(calls[1].body.categories, "后端技术");
    assert.equal(calls[1].body.tags, "cloudflare,typescript");
    assert.match(calls[1].body.content, /src="https:\/\/img\.csdn\.net\/hosted\/diagram\.png"/);
    assert.match(calls[1].body.content, /https:\/\/dinqor\.cn\/blog\/301/);
  } finally {
    globalThis.fetch = previousFetch;
  }
});

test("CSDN Markdown conversion escapes raw HTML and drops active or local URLs", async () => {
  const previousFetch = globalThis.fetch;
  let saveBody;
  let calls = 0;
  globalThis.fetch = async (_url, init) => {
    calls++;
    saveBody = JSON.parse(init.body);
    return Response.json({ code: 200, data: { article_id: "302", url: "https://blog.csdn.net/alice/article/details/302" } });
  };
  try {
    const mapper = new CsdnPublicationMapper({ PLATFORM_CSDN_API_KEY: "key", PLATFORM_CSDN_WEB_CONSOLE_SECRET: "secret" });
    await mapper.publish({ cookie: "CSDN=session" }, input({
      postId: 302,
      content: '<script>alert("x")</script>\n\n[bad](javascript:alert(1))\n\n![local](http://127.0.0.1/private.png)',
    }));
    assert.equal(calls, 1);
    assert.match(saveBody.content, /&lt;script&gt;/);
    assert.doesNotMatch(saveBody.content, /<script|href="javascript:|src="http:\/\/127\.0\.0\.1/);
  } finally {
    globalThis.fetch = previousFetch;
  }
});

test("CSDN expired-session errors invalidate the user's stored credential", async () => {
  let expired = false;
  const credentials = {
    async require(userId, platform) { assert.equal(userId, 42); assert.equal(platform, "csdn"); return { cookie: "CSDN=session" }; },
    async markExpired(userId, platform) { expired = userId === 42 && platform === "csdn"; },
  };
  const service = new CsdnPublicationService(credentials, {
    async publish() { throw new CsdnProviderError(true, "session expired"); },
  });
  await assert.rejects(service.publish(input()),
    (error) => error instanceof PlatformCredentialError && error.status === 409);
  assert.equal(expired, true);
});

test("CSDN publication controller validates its owner projection", async () => {
  const calls = [];
  const service = { async publish(value) { calls.push(value); return { platform: "csdn", externalId: "301", url: "https://blog.csdn.net/a/301" }; } };
  const path = "https://integration.test/internal/platform-integration/blog-posts/301/sync/csdn";
  const response = await handleCsdnPublicationRequest(new Request(path, {
    method: "POST", headers: { "content-type": "application/json" },
    body: JSON.stringify({ userId: 42, postId: 301, post: { ...input(), id: 301, categoryName: "后端技术" } }),
  }), service);
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { platform: "csdn", externalId: "301", url: "https://blog.csdn.net/a/301" });
  assert.equal(calls[0].category, "后端技术");
  assert.equal(calls[0].userId, 42);

  const mismatch = await handleCsdnPublicationRequest(new Request(path, {
    method: "POST", body: JSON.stringify({ userId: 42, postId: 302, post: { title: "x", content: "y" } }),
  }), service);
  assert.equal(mismatch.status, 400);
  const oversized = await handleCsdnPublicationRequest(new Request(path, {
    method: "POST", headers: { "content-length": "5000000" }, body: "{}",
  }), service);
  assert.equal(oversized.status, 413);
});

test("CSDN publication endpoint requires the internal service token and uses the user's encrypted cookie", async () => {
  const db = d1Fixture();
  const env = {
    DB: db,
    INTERNAL_SERVICE_TOKEN: token,
    PLATFORM_CREDENTIAL_ENCRYPTION_KEY: encryptionKey,
    PLATFORM_CSDN_API_KEY: "api-key",
    PLATFORM_CSDN_WEB_CONSOLE_SECRET: "web-secret",
  };
  const importRequest = new Request("https://integration.test/internal/v1/platform-credentials/42/csdn", {
    method: "PUT",
    headers: { "content-type": "application/json", "x-dinqor-service-token": token },
    body: JSON.stringify({ credential: { cookie: "UserToken=private-cookie", username: "alice" }, accountName: "alice" }),
  });
  assert.equal((await integrationWorker.fetch(importRequest, env)).status, 200);
  const denied = await integrationWorker.fetch(new Request(
    "https://integration.test/internal/platform-integration/blog-posts/301/sync/csdn", {
      method: "POST", headers: { "content-type": "application/json", "x-dinqor-service-token": "wrong" },
      body: JSON.stringify({ userId: 42, postId: 301, post: { title: "Title", content: "Body" } }),
    },
  ), env);
  assert.equal(denied.status, 401);

  const previousFetch = globalThis.fetch;
  let requestHeaders;
  globalThis.fetch = async (_url, init) => {
    requestHeaders = new Headers(init.headers);
    return Response.json({ code: 200, data: { article_id: "301", url: "https://blog.csdn.net/alice/301" } });
  };
  try {
    const published = await integrationWorker.fetch(new Request(
      "https://integration.test/internal/platform-integration/blog-posts/301/sync/csdn", {
        method: "POST", headers: { "content-type": "application/json", "x-dinqor-service-token": token },
        body: JSON.stringify({ userId: 42, postId: 301, post: { id: 301, title: "Title", content: "Body" } }),
      },
    ), env);
    assert.equal(published.status, 200);
    const result = await published.json();
    assert.deepEqual(result, {
      platform: "csdn", externalId: "301", url: "https://blog.csdn.net/alice/301",
    });
    assert.equal(requestHeaders.get("cookie"), "UserToken=private-cookie");
    assert.doesNotMatch(JSON.stringify(result), /private-cookie/);
  } finally {
    globalThis.fetch = previousFetch;
  }
});
