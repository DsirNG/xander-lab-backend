import assert from "node:assert/strict";
import test from "node:test";
import { createHmac } from "node:crypto";
import { CsdnPublicationMapper } from "../.test-build/mappers/csdn-publication-mapper.js";
import { PlatformConnectionService } from "../.test-build/services/platform-connection-service.js";
import { handlePlatformConnectionRequest } from "../.test-build/controllers/platform-connection-controller.js";
import { CsdnProviderError } from "../.test-build/interfaces/csdn-publication-port.js";
import { PlatformCredentialError } from "../.test-build/services/platform-credential-service.js";

const env = { PLATFORM_CSDN_API_KEY: "publishing-key", PLATFORM_CSDN_WEB_CONSOLE_SECRET: "publishing-secret",
  PLATFORM_CSDN_ACCOUNT_API_KEY: "test-key", PLATFORM_CSDN_ACCOUNT_API_SECRET: "test-secret" };

test("CSDN current-account request signs a bounded authenticated GET and returns only profile fields", async (context) => {
  const original = globalThis.fetch;
  context.after(() => { globalThis.fetch = original; });
  globalThis.fetch = async (url, init) => {
    assert.equal(String(url), "https://bizapi.csdn.net/blog-console-api/v1/user/info");
    assert.equal(init.method, "GET");
    assert.equal(init.redirect, "manual");
    assert.equal(init.headers["x-ca-stage"], "");
    assert.ok(init.signal);
    assert.equal(init.headers.cookie, "UserToken=private-cookie");
    const canonical = `GET\n${init.headers.accept}\n\n\n\nx-ca-key:test-key\nx-ca-nonce:${init.headers["x-ca-nonce"]}\n/blog-console-api/v1/user/info`;
    assert.equal(init.headers["x-ca-signature"], createHmac("sha256", "test-secret").update(canonical).digest("base64"));
    return Response.json({ code: 200, data: { username: "alice", nickname: "Alice", avatar: "https://avatar.csdn.net/a.png", mobile: "private", token: "private" } });
  };
  const profile = await new CsdnPublicationMapper(env).getCurrentAccount({ cookie: "UserToken=private-cookie" });
  assert.deepEqual(profile, { username: "alice", nickname: "Alice", avatarUrl: "https://avatar.csdn.net/a.png" });
});

test("CSDN verification rejects login, invalid JSON, redirects, signature failures and incomplete profiles", async (context) => {
  const original = globalThis.fetch;
  context.after(() => { globalThis.fetch = original; });
  const cases = [
    [() => Response.json({ code: 401 }, { status: 401 }), true],
    [() => Response.json({ code: 401 }), true],
    [() => new Response(null, { status: 302, headers: { location: "https://passport.csdn.net/account/login" } }), true],
    [() => Response.json({ message: "invalid signature" }, { status: 403 }), false],
    [() => Response.json({ message: "X-Ca-Key is not exist" }, { status: 401 }), false],
    [() => new Response("<html>challenge</html>"), false],
    [() => Response.json({ code: 200, data: { nickname: "Public profile" } }), false],
    [() => new Response("{}", { headers: { "content-length": "2000000" } }), false],
    [() => { throw new Error("private details"); }, false],
  ];
  for (const [response, expired] of cases) {
    globalThis.fetch = async () => response();
    await assert.rejects(new CsdnPublicationMapper(env).getCurrentAccount({ cookie: "UserToken=secret" }),
      (error) => error instanceof CsdnProviderError && error.sessionExpired === expired && !error.message.includes("private"));
  }
});

test("connection controller checks identity, uses only the owner credential, and distinguishes platform expiry from site login", async () => {
  let owner;
  let failure = null;
  const service = new PlatformConnectionService({
    async requireForVerification(userId, platform) { owner = userId; assert.equal(platform, "csdn"); return { credential: { cookie: "UserToken=secret" }, reference: "snapshot", authorizedAt: "2026-10-12T08:00:00Z" }; }, async saveAccountProfile() {},
  }, {
    async getCurrentAccount() { if (failure) throw failure; return { username: "alice", nickname: null, avatarUrl: null }; },
  });
  const request = (headers = {}) => new Request("https://api.test/api/publishing/csdn/connection/test", { method: "POST", headers });
  assert.equal((await handlePlatformConnectionRequest(request(), service)).status, 401);
  assert.equal(owner, undefined);
  const result = await handlePlatformConnectionRequest(request({ "x-user-id": "42" }), service);
  assert.equal(owner, 42);
  assert.equal(result.headers.get("cache-control"), "no-store");
  const body = await result.json();
  assert.equal(body.data.username, "alice");
  assert.ok(Date.parse(body.data.verifiedAt));
  assert.ok(!JSON.stringify(body).includes("secret"));
  for (const [error, status] of [[new CsdnProviderError(true), 409], [new CsdnProviderError(false), 502]]) {
    failure = error;
    const response = await handlePlatformConnectionRequest(request({ "x-user-id": "42" }), service);
    assert.equal(response.status, 200);
    const body = await response.json();
    assert.equal(body.code, status);
    assert.equal(body.data, null);
  }
  const missing = new PlatformConnectionService({ requireForVerification: async () => { throw new PlatformCredentialError(409, "expired"); } }, {});
  const missingResponse = await handlePlatformConnectionRequest(request({ "x-user-id": "42" }), missing);
  assert.equal(missingResponse.status, 200);
  assert.equal((await missingResponse.json()).code, 409);
});
