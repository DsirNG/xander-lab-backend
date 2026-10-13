import assert from "node:assert/strict";
import test from "node:test";
import { handleJuejinPublicationRequest } from "../.test-build/controllers/juejin-publication-controller.js";
import { JuejinCatalogMapper } from "../.test-build/mappers/juejin-catalog-mapper.js";
import { JuejinCatalogProviderError } from "../.test-build/interfaces/juejin-catalog-port.js";
import { JuejinPublicationService } from "../.test-build/services/juejin-publication-service.js";
import { PlatformCredentialError } from "../.test-build/services/platform-credential-service.js";

function requestFixture(overrides = {}) {
  return {
    userId: 42,
    postId: 301,
    post: {
      id: 301,
      title: "Worker architecture",
      summary: "",
      content: "# Introduction\nA practical article body with enough content for publishing.",
      category: "frontend",
      categoryName: "前端开发",
      coverImage: "https://cdn.example.test/cover.png",
      tags: ["typescript", "6809640407484334093", "javascript", "cloudflare"],
    },
    ...overrides,
  };
}

test("Juejin publication resolves catalog IDs, shapes its brief, and publishes a draft", async () => {
  const calls = [];
  const credentials = {
    async require(userId, platform) {
      assert.equal(userId, 42);
      assert.equal(platform, "juejin");
      return { cookie: "sessionid=abc" };
    },
  };
  const catalog = {
    async ensureFresh() {},
    async resolveCategoryId(value) { assert.equal(value, "前端开发"); return "6809637767543259144"; },
    async resolveTagId(value) { return value === "typescript" ? "tag-typescript" : null; },
  };
  const provider = {
    async searchTags(keyword, limit) {
      calls.push({ search: keyword, limit });
      return [{ id: "tag-javascript", name: "JavaScript" }];
    },
    async createDraft(credential, input) { calls.push({ credential, input }); return "draft-301"; },
    async publishDraft(credential, id) { calls.push({ publish: id }); return "article-987"; },
  };
  const service = new JuejinPublicationService(credentials, catalog, provider);
  const result = await service.publish(requestFixture());
  assert.deepEqual(result, {
    platform: "juejin", externalId: "article-987", url: "https://juejin.cn/post/article-987",
  });
  const create = calls.find((call) => call.input);
  assert.equal(create.input.categoryId, "6809637767543259144");
  assert.deepEqual(create.input.tagIds, ["tag-typescript", "6809640407484334093", "tag-javascript"]);
  assert.equal(create.input.briefContent.length, 50);
  assert.equal(create.input.markdownContent, requestFixture().post.content);
  assert.deepEqual(calls.find((call) => call.search), { search: "javascript", limit: 8 });
  assert.deepEqual(calls.find((call) => call.publish), { publish: "draft-301" });
});

test("Juejin publication marks an expired provider session and rejects mismatched article IDs", async () => {
  let expired = false;
  const credentials = {
    async require() { return { cookie: "sessionid=abc" }; },
    async markExpired(userId, platform) { expired = userId === 42 && platform === "juejin"; },
  };
  const catalog = { async ensureFresh() {}, async resolveCategoryId() { return "1"; }, async resolveTagId() { return null; } };
  const provider = {
    async searchTags() { return []; },
    async createDraft() { throw new JuejinCatalogProviderError(true); },
    async publishDraft() { throw new Error("must not publish"); },
  };
  const service = new JuejinPublicationService(credentials, catalog, provider);
  await assert.rejects(service.publish(requestFixture()),
    (error) => error instanceof PlatformCredentialError && error.status === 409);
  assert.equal(expired, true);
  await assert.rejects(service.publish({ ...requestFixture(), post: { ...requestFixture().post, id: 302 } }),
    (error) => error instanceof PlatformCredentialError && error.status === 400);
});

test("Juejin API mapper sends legacy draft and publish payloads and keeps provider IDs lossless", async () => {
  const previousFetch = globalThis.fetch;
  const calls = [];
  globalThis.fetch = async (url, init) => {
    calls.push({ url: new URL(url), init });
    const body = calls.length === 1
      ? '{"err_no":0,"data":{"id":1234567890123456789}}'
      : '{"err_no":0,"data":{"article_id":9876543210987654321}}';
    return new Response(body, { status: 200 });
  };
  try {
    const mapper = new JuejinCatalogMapper({});
    const credential = { cookie: "sessionid=abc", csrfToken: "csrf", aid: "2608", uuid: "uuid-1" };
    const draftId = await mapper.createDraft(credential, {
      title: "Title", briefContent: "a".repeat(50), markdownContent: "# body",
      categoryId: "6809637767543259144", tagIds: ["6809640407484334093"],
      coverImage: "https://cdn.example.test/cover.png",
    });
    const articleId = await mapper.publishDraft(credential, draftId);
    assert.equal(draftId, "1234567890123456789");
    assert.equal(articleId, "9876543210987654321");
    assert.equal(calls[0].url.pathname, "/content_api/v1/article_draft/create");
    assert.equal(calls[0].url.searchParams.get("aid"), "2608");
    assert.equal(calls[0].init.headers.cookie, credential.cookie);
    assert.equal(calls[0].init.headers["x-secsdk-csrf-token"], credential.csrfToken);
    assert.deepEqual(JSON.parse(calls[0].init.body), {
      title: "Title", brief_content: "a".repeat(50), mark_content: "# body",
      category_id: "6809637767543259144", tag_ids: ["6809640407484334093"],
      link_url: "", cover_image: "https://cdn.example.test/cover.png", edit_type: 10,
      html_content: "deprecated", theme_ids: [], pics: [],
    });
    assert.equal(calls[1].url.pathname, "/content_api/v1/article/publish");
    assert.deepEqual(JSON.parse(calls[1].init.body), {
      draft_id: "1234567890123456789", sync_to_org: false, column_ids: [], theme_ids: [],
    });
  } finally {
    globalThis.fetch = previousFetch;
  }
});

test("Juejin publication controller validates internal payload ownership and returns the sync result", async () => {
  const calls = [];
  const service = { async publish(value) { calls.push(value); return { platform: "juejin", externalId: "x1", url: "https://juejin.cn/post/x1" }; } };
  const path = "https://integration.test/internal/platform-integration/blog-posts/301/sync/juejin";
  const response = await handleJuejinPublicationRequest(new Request(path, {
    method: "POST", headers: { "content-type": "application/json" },
    body: JSON.stringify(requestFixture()),
  }), service);
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { platform: "juejin", externalId: "x1", url: "https://juejin.cn/post/x1" });
  assert.equal(calls[0].userId, 42);
  assert.equal(calls[0].postId, 301);

  const mismatch = await handleJuejinPublicationRequest(new Request(path, {
    method: "POST", body: JSON.stringify({ ...requestFixture(), postId: 302 }),
  }), service);
  assert.equal(mismatch.status, 400);
  const oversized = await handleJuejinPublicationRequest(new Request(path, {
    method: "POST", headers: { "content-length": "5000000" }, body: "{}",
  }), service);
  assert.equal(oversized.status, 413);
});

test("expired Juejin catalog blocks draft creation even when catalog outages allow fallback", async () => {
  let drafts = 0;
  const service = new JuejinPublicationService(
    { async require() { return { cookie: "test-session" }; } },
    { async ensureFresh() { throw new PlatformCredentialError(409, "掘金授权已失效，请重新授权"); } },
    { async createDraft() { drafts += 1; } },
  );
  await assert.rejects(service.publish(requestFixture()), (error) => error.status === 409);
  assert.equal(drafts, 0);
});
