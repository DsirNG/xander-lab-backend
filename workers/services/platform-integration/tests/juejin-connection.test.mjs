import assert from "node:assert/strict";
import test from "node:test";
import { JuejinCatalogMapper } from "../.test-build/mappers/juejin-catalog-mapper.js";
import { JuejinCatalogProviderError } from "../.test-build/interfaces/juejin-catalog-port.js";
import { PlatformConnectionService } from "../.test-build/services/platform-connection-service.js";
import { handlePlatformConnectionRequest } from "../.test-build/controllers/platform-connection-controller.js";

test("Juejin reads the current session without user_id and preserves long account IDs", async (context) => {
  const original = globalThis.fetch;
  context.after(() => { globalThis.fetch = original; });
  globalThis.fetch = async (url, init) => {
    assert.equal(url.origin, "https://api.juejin.cn");
    assert.equal(url.pathname, "/user_api/v1/user/get");
    assert.equal(url.searchParams.has("user_id"), false);
    assert.equal(url.searchParams.get("aid"), "2608");
    assert.equal(init.method, "GET");
    assert.equal(init.headers.cookie, "sessionid=private");
    assert.equal(init.redirect, "manual");
    assert.ok(init.signal);
    return new Response('{"err_no":0,"data":{"user_id":1234567890123456789,"user_name":"Alice","avatar_large":"https://p3-juejin.byteimg.com/avatar.png","mobile":"private"}}');
  };
  const profile = await new JuejinCatalogMapper({}).getCurrentAccount({ cookie: "sessionid=private" });
  assert.deepEqual(profile, { username: "1234567890123456789", nickname: "Alice", avatarUrl: "https://p3-juejin.byteimg.com/avatar.png" });
});

test("Juejin anonymous success is not verification; provider challenges and malformed profiles fail", async (context) => {
  const original = globalThis.fetch;
  context.after(() => { globalThis.fetch = original; });
  for (const [body, expired] of [
    [{ err_no: 0, data: null }, true],
    [{ err_no: 401, err_msg: "unauthorized" }, true],
    [{ err_no: 2, err_msg: "参数错误" }, false],
    [{ err_no: 0, data: { user_name: "Public user" } }, false],
    [{ data: { user_id: "123", user_name: "Alice" } }, false],
  ]) {
    globalThis.fetch = async () => Response.json(body);
    await assert.rejects(new JuejinCatalogMapper({}).getCurrentAccount({ cookie: "sessionid=private" }),
      (error) => error instanceof JuejinCatalogProviderError && error.sessionExpired === expired);
  }
});

test("Juejin connection endpoint uses the owner's Juejin credential", async () => {
  const service = new PlatformConnectionService({
    async requireForVerification(owner, platform) { assert.equal(owner, 42); assert.equal(platform, "juejin"); return { credential: { cookie: "sessionid=private" }, reference: "snapshot", authorizedAt: "2026-10-12T08:00:00Z" }; }, async saveAccountProfile() {},
  }, { async getCurrentAccount() { return { username: "123", nickname: "Alice", avatarUrl: null }; } });
  const result = await handlePlatformConnectionRequest(new Request("https://api.test/api/publishing/juejin/connection/test", {
    method: "POST", headers: { "x-user-id": "42" },
  }), service);
  assert.equal(result.status, 200);
  assert.equal((await result.json()).data.username, "123");
});
