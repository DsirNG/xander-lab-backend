import assert from "node:assert/strict";
import test from "node:test";
import { handlePlatformBlogSyncRequest } from "../.test-build/controllers/platform-blog-sync-controller.js";
import { ContentBlogPublicationMapper } from "../.test-build/mappers/content-blog-publication-mapper.js";
import { PlatformBlogSyncService } from "../.test-build/services/platform-blog-sync-service.js";

function request(path, { method = "POST", userId = "42", body } = {}) {
  return new Request(`https://platform.example.test${path}`, {
    method,
    headers: { ...(userId ? { "x-user-id": userId } : {}), ...(body === undefined ? {} : { "content-type": "application/json" }) },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
}

test("public CSDN and Juejin sync routes use the Gateway owner and Content publication ledger", async () => {
  const calls = [];
  const service = new PlatformBlogSyncService({
    async sync(input) { calls.push(input); return { platform: input.platform, externalId: "vendor-81", url: null }; },
  });
  const unauthorized = await handlePlatformBlogSyncRequest(request("/api/blog/posts/81/sync/csdn", { userId: null }), service);
  assert.equal(unauthorized.status, 401);

  const csdn = await handlePlatformBlogSyncRequest(request("/api/blog/posts/81/sync/csdn"), service);
  assert.equal(csdn.status, 200);
  assert.deepEqual((await csdn.json()).data, { platform: "csdn", externalId: "vendor-81", url: null });
  assert.deepEqual(calls[0], { userId: 42, postId: 81, platform: "csdn" });

  const juejin = await handlePlatformBlogSyncRequest(request("/api/blog/posts/81/sync/juejin", {
    body: { juejinCategoryId: "6809637767543259144", juejinTagIds: ["6809640407484334093"] },
  }), service);
  assert.equal(juejin.status, 200);
  assert.deepEqual(calls[1], {
    userId: 42, postId: 81, platform: "juejin",
    categoryId: "6809637767543259144", tagIds: ["6809640407484334093"],
  });

  const malformed = await handlePlatformBlogSyncRequest(request("/api/blog/posts/81/sync/juejin", {
    body: { juejinTagIds: [1] },
  }), service);
  assert.equal(malformed.status, 400);
  assert.equal(calls.length, 2);
});

test("Content publication mapper protects the cross-account route and validates provider results", async () => {
  const requests = [];
  const env = {
    CONTENT_AUTOMATION_SERVICE_URL: "https://content.example.test/base",
    CONTENT_AUTOMATION_SERVICE_TOKEN: "private-content-token",
  };
  const mapper = new ContentBlogPublicationMapper(env, async (url, init) => {
    requests.push({ url, init });
    return Response.json({ platform: "juejin", externalId: "provider-81", url: "https://juejin.cn/post/81" });
  });
  const result = await mapper.sync({
    userId: 42, postId: 81, platform: "juejin",
    juejinCategoryId: "6809637767543259144", juejinTagIds: ["6809640407484334093"],
  });
  assert.deepEqual(result, { platform: "juejin", externalId: "provider-81", url: "https://juejin.cn/post/81" });
  assert.equal(requests[0].url.href, "https://content.example.test/internal/agent/publications/81/sync/juejin");
  assert.equal(new Headers(requests[0].init.headers).get("x-dinqor-service-token"), "private-content-token");
  assert.deepEqual(JSON.parse(requests[0].init.body), {
    userId: 42, postId: 81, juejinCategoryId: "6809637767543259144", juejinTagIds: ["6809640407484334093"],
  });

  const insecure = new ContentBlogPublicationMapper({
    ...env, CONTENT_AUTOMATION_SERVICE_URL: "http://content.example.test",
  }, async () => { throw new Error("must not make an insecure request"); });
  await assert.rejects(insecure.sync({ userId: 42, postId: 81, platform: "csdn" }), /HTTPS/);
});

test("Content ownership, cooldown, and provider failures keep their HTTP status", async () => {
  const env = {
    CONTENT_AUTOMATION_SERVICE_URL: "https://content.example.test",
    CONTENT_AUTOMATION_SERVICE_TOKEN: "private-content-token",
  };
  for (const status of [404, 409, 429]) {
    const mapper = new ContentBlogPublicationMapper(env, async () => Response.json({ message: "request rejected" }, { status }));
    await assert.rejects(
      mapper.sync({ userId: 42, postId: 81, platform: "csdn" }),
      (error) => error.status === status && error.message === "request rejected",
    );
  }
});
