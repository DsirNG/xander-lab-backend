import assert from "node:assert/strict";
import test from "node:test";
import { handleJuejinCatalogRequest } from "../.test-build/controllers/juejin-catalog-controller.js";
import { JuejinCatalogMapper } from "../.test-build/mappers/juejin-catalog-mapper.js";
import { JuejinCatalogProviderError } from "../.test-build/interfaces/juejin-catalog-port.js";
import { PlatformCredentialError, PlatformCredentialService } from "../.test-build/services/platform-credential-service.js";
import { DEFAULT_JUEJIN_CATEGORY_ID, JuejinCatalogService } from "../.test-build/services/juejin-catalog-service.js";

const secretKey = Buffer.alloc(32, 8).toString("base64");

function makeCatalogService({ provider, repository, credentials } = {}) {
  const state = repository ?? {
    categories: [], tags: [], synced: 0,
    async upsert(categories, tags) { this.categories.push(...categories); this.tags.push(...tags); },
    async markSynced() { this.synced += 1; },
    async isFresh() { return false; },
    async findCategoryId() { return null; },
    async findTagId() { return null; },
  };
  const credentialService = credentials ?? { async require() { return { cookie: "sessionid=abc" }; } };
  return {
    state,
    service: new JuejinCatalogService(credentialService, state, provider ?? {
      async queryCategories() { return []; },
      async queryTags() { return { tags: [], hasMore: false, cursor: "0" }; },
    }),
  };
}

test("catalog sync persists categories, hot tags, and bounded global tag pages", async () => {
  const cursors = [];
  const provider = {
    async queryCategories() {
      return [{ id: "6809637767543259144", name: "前端", rank: 2,
        hotTags: [{ id: "6809640407484334093", name: "JavaScript" }] }];
    },
    async queryTags(_credential, cursor, limit) {
      cursors.push([cursor, limit]);
      return { tags: [{ id: `tag-${cursors.length}`, name: `标签${cursors.length}` }],
        hasMore: true, cursor: `cursor-${cursors.length}` };
    },
  };
  const { service, state } = makeCatalogService({ provider });
  await service.sync(42);
  assert.equal(cursors.length, 10);
  assert.deepEqual(cursors[0], ["0", 100]);
  assert.equal(state.categories.length, 1);
  assert.equal(state.tags.length, 11);
  assert.equal(state.synced, 1);
  assert.equal(state.categories[0].categoryId, "6809637767543259144");
});

test("catalog sync rejects a repeated pagination cursor without persisting partial data", async () => {
  let persisted = false;
  const repository = {
    async upsert() { persisted = true; }, async markSynced() {}, async isFresh() { return false; },
    async findCategoryId() { return null; }, async findTagId() { return null; },
  };
  const provider = {
    async queryCategories() { return []; },
    async queryTags() { return { tags: [{ id: "tag-1", name: "标签" }], hasMore: true, cursor: "0" }; },
  };
  const { service } = makeCatalogService({ provider, repository });
  await assert.rejects(service.sync(42), (error) => error instanceof PlatformCredentialError && error.status === 503);
  assert.equal(persisted, false);
});

test("catalog sync marks expired Juejin sessions and returns an authentication error", async () => {
  let expired = false;
  const credentials = {
    async require() { return { cookie: "sessionid=expired" }; },
    async markExpired(userId, platform) { expired = userId === 42 && platform === "juejin"; },
  };
  const provider = {
    async queryCategories() { throw new JuejinCatalogProviderError(true); },
    async queryTags() { throw new Error("must not be called"); },
  };
  const { service } = makeCatalogService({ provider, credentials });
  await assert.rejects(service.sync(42), (error) => error instanceof PlatformCredentialError && error.status === 401);
  assert.equal(expired, true);
});

test("Juejin mapper posts the legacy query shape and preserves 64-bit provider IDs", async () => {
  const previousFetch = globalThis.fetch;
  const calls = [];
  globalThis.fetch = async (url, init) => {
    calls.push({ url: new URL(url), init });
    const body = calls.length === 1
      ? '{"err_no":0,"data":[{"category":{"category_id":6809637767543259144,"category_name":"前端","rank":1},"hot_tags":[{"tag_id":6809640407484334093,"tag_name":"JavaScript"}]}]}'
      : '{"err_no":0,"data":[{"tag_id":6809640407484334093,"tag":{"tag_name":"JavaScript"}}],"has_more":false,"cursor":"0"}';
    return new Response(body, { status: 200, headers: { "content-type": "application/json" } });
  };
  try {
    const mapper = new JuejinCatalogMapper({});
    const credential = { cookie: "sessionid=abc", csrfToken: "csrf-value", uuid: "uuid-1" };
    const categories = await mapper.queryCategories(credential);
    const tags = await mapper.queryTags(credential, "0", 100);
    assert.equal(categories[0].id, "6809637767543259144");
    assert.equal(categories[0].hotTags[0].id, "6809640407484334093");
    assert.equal(tags.tags[0].id, "6809640407484334093");
    assert.equal(calls[0].url.pathname, "/tag_api/v1/query_category_list");
    assert.equal(calls[0].url.searchParams.get("aid"), "2608");
    assert.equal(calls[0].url.searchParams.get("uuid"), "uuid-1");
    assert.equal(calls[0].init.method, "POST");
    assert.equal(calls[0].init.headers.cookie, credential.cookie);
    assert.equal(calls[0].init.headers["x-secsdk-csrf-token"], credential.csrfToken);
    assert.deepEqual(JSON.parse(calls[1].init.body), { cursor: "0", key_word: "", limit: 100, sort_type: 1 });
  } finally {
    globalThis.fetch = previousFetch;
  }
});

test("catalog controller exposes the authenticated public command and internal sync contract", async () => {
  const owners = [];
  const service = { async sync(userId) { owners.push(userId); } };
  const response = await handleJuejinCatalogRequest(
    new Request("https://integration.test/internal/platform-integration/platform-credentials/42/juejin/catalog/sync", { method: "POST" }),
    service,
  );
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { synced: true });
  assert.deepEqual(owners, [42]);
  assert.equal(await handleJuejinCatalogRequest(
    new Request("https://integration.test/api/publishing/juejin/catalog/sync", { method: "POST" }), service,
  ).then((response) => response.status), 401);
  const publicSync = await handleJuejinCatalogRequest(new Request(
    "https://integration.test/api/publishing/juejin/catalog/sync",
    { method: "POST", headers: { "x-user-id": "43" } },
  ), service);
  assert.deepEqual(await publicSync.json(), { code: 200, message: "success", data: null });
  assert.deepEqual(owners, [42, 43]);
  assert.equal(DEFAULT_JUEJIN_CATEGORY_ID, "6809637767543259144");
});

test("credential import rejects line breaks in provider request headers", async () => {
  const service = new PlatformCredentialService({ async save() {}, async find() { return null; } }, secretKey);
  await assert.rejects(service.save(42, "juejin", { cookie: "sessionid=abc\r\nX-Evil: true" }, null), /凭证格式/);
  await assert.rejects(service.save(42, "juejin", { cookie: "sessionid=abc", csrfToken: "csrf\nX-Evil: true" }, null), /凭证格式/);
});
