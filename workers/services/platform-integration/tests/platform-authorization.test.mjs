import assert from "node:assert/strict";
import test from "node:test";
import { WebDriverPlatformBrowserMapper } from "../.test-build/mappers/web-driver-platform-browser-mapper.js";
import { PlatformAuthorizationService } from "../.test-build/services/platform-authorization-service.js";
import { PlatformCredentialService } from "../.test-build/services/platform-credential-service.js";

const key = Buffer.alloc(32, 19).toString("base64");

function fixture() {
  const slots = new Map();
  const queue = [];
  const records = new Map();
  let queueId = 0;
  const repository = {
    async findSlot(platform) { return slots.has(platform) ? { ...slots.get(platform) } : null; },
    async tryAcquireSlot(platform, userId, expiresAt) {
      if (slots.has(platform)) return false;
      slots.set(platform, { platform, userId, encryptedSessionId: null, startingAt: null, expiresAt });
      return true;
    },
    async tryMarkStarting(platform, userId) {
      const slot = slots.get(platform);
      if (!slot || slot.userId !== userId || slot.encryptedSessionId || slot.startingAt) return false;
      slot.startingAt = new Date().toISOString();
      return true;
    },
    async saveSessionId(platform, userId, encryptedSessionId) {
      const slot = slots.get(platform);
      if (!slot || slot.userId !== userId || slot.encryptedSessionId || !slot.startingAt) return false;
      slot.encryptedSessionId = encryptedSessionId;
      return true;
    },
    async deleteSlot(platform, userId) {
      if (slots.get(platform)?.userId === userId) slots.delete(platform);
    },
    async enqueue(platform, userId, expiresAt) {
      const current = queue.find((entry) => entry.platform === platform && entry.userId === userId);
      if (current) current.expiresAt = expiresAt;
      else queue.push({ id: ++queueId, platform, userId, expiresAt });
    },
    async findQueueEntry(platform, userId) {
      const sorted = queue.filter((entry) => entry.platform === platform && Date.parse(entry.expiresAt) > Date.now())
        .sort((a, b) => a.id - b.id);
      const index = sorted.findIndex((entry) => entry.userId === userId);
      return index < 0 ? null : {
        platform, userId, queuePosition: index + 1, estimatedWaitSeconds: index * 300,
      };
    },
    async claimNext(platform, expiresAt) {
      if (slots.has(platform)) return null;
      const index = queue.findIndex((entry) => entry.platform === platform && Date.parse(entry.expiresAt) > Date.now());
      if (index < 0) return null;
      const [entry] = queue.splice(index, 1);
      slots.set(platform, { platform, userId: entry.userId, encryptedSessionId: null, startingAt: null, expiresAt });
      return entry.userId;
    },
    async deleteQueueEntry(platform, userId) {
      const index = queue.findIndex((entry) => entry.platform === platform && entry.userId === userId);
      if (index >= 0) queue.splice(index, 1);
    },
    async removeExpired(now) {
      const expired = [];
      for (const [platform, slot] of slots) {
        if (Date.parse(slot.expiresAt) <= Date.parse(now)) {
          expired.push({ ...slot });
          slots.delete(platform);
        }
      }
      for (let i = queue.length - 1; i >= 0; i--) {
        if (Date.parse(queue[i].expiresAt) <= Date.parse(now)) queue.splice(i, 1);
      }
      return expired;
    },
  };
  const credentialRows = new Map();
  const credentialRepository = {
    async find(userId, platform) { return credentialRows.get(`${userId}:${platform}`) ?? null; },
    async save(record) {
      const saved = { ...record, status: "ACTIVE", authorizedAt: new Date().toISOString() };
      credentialRows.set(`${record.userId}:${record.platform}`, saved);
      records.set(`${record.userId}:${record.platform}`, saved);
    },
    async delete(userId, platform) { credentialRows.delete(`${userId}:${platform}`); },
    async markExpired(userId, platform) {
      const record = credentialRows.get(`${userId}:${platform}`);
      if (record) record.status = "EXPIRED";
    },
  };
  const credentials = new PlatformCredentialService(credentialRepository, key);
  const sessions = new Map();
  const closed = [];
  let sessionNumber = 0;
  const browser = {
    async start(platform) {
      const sessionId = `session-${++sessionNumber}`;
      sessions.set(sessionId, []);
      return { sessionId, qrCodeDataUrl: `data:image/png;base64,${platform}` };
    },
    async qrCode(platform) { return `data:image/png;base64,${platform}`; },
    async cookies(sessionId) { return sessions.get(sessionId) ?? []; },
    async close(sessionId) { closed.push(sessionId); sessions.delete(sessionId); },
  };
  const service = new PlatformAuthorizationService(repository, browser, credentials);
  return { service, repository, slots, queue, browser, sessions, closed, credentials, credentialRows, records };
}

test("Platform QR sessions are encrypted, owner-bound, and harvested into encrypted credentials", async () => {
  const value = fixture();
  const started = await value.service.start(71, "csdn");
  assert.deepEqual(started, {
    status: "PENDING", qrCodeDataUrl: "data:image/png;base64,csdn", expiresInSeconds: 35,
    queuePosition: null, estimatedWaitSeconds: null,
  });
  const slot = value.slots.get("csdn");
  assert.ok(slot.encryptedSessionId);
  assert.doesNotMatch(slot.encryptedSessionId, /session-1/);
  await assert.rejects(value.credentials.revealAuthorizationSession(72, "csdn", slot.encryptedSessionId));

  value.sessions.get("session-1").push(
    { name: "UserName", value: "ada" },
    { name: "UserToken", value: "provider-token" },
    { name: "z_cookie", value: "last" },
  );
  const status = await value.service.status(71, "csdn");
  assert.equal(status.status, "AUTHORIZED");
  assert.equal(value.slots.has("csdn"), false);
  assert.deepEqual(value.closed, ["session-1"]);
  assert.deepEqual(await value.credentials.require(71, "csdn"), {
    cookie: "UserName=ada; UserToken=provider-token; z_cookie=last", username: "ada",
  });
  assert.equal((await value.service.status(72, "csdn")).status, "NOT_AUTHORIZED");
});

test("Platform authorization queue promotes the next owner after cancel and removes browser sessions on disconnect", async () => {
  const value = fixture();
  await value.service.start(81, "juejin");
  const queued = await value.service.start(82, "juejin");
  assert.equal(queued.status, "QUEUED");
  assert.equal(queued.queuePosition, 1);

  await value.service.cancel(81, "juejin");
  const promoted = await value.service.status(82, "juejin");
  assert.equal(promoted.status, "PENDING");
  assert.equal(promoted.qrCodeDataUrl, "data:image/png;base64,juejin");
  const session = value.slots.get("juejin").encryptedSessionId;
  assert.ok(session);

  value.sessions.get("session-2").push({ name: "sessionid_ss", value: "signed-session" });
  assert.equal((await value.service.status(82, "juejin")).status, "AUTHORIZED");
  assert.deepEqual(await value.credentials.require(82, "juejin"), {
    cookie: "sessionid_ss=signed-session", csrfToken: "", uuid: "", sessionKey: "signed-session",
  });
  await value.service.disconnect(82, "juejin");
  assert.equal((await value.service.status(82, "juejin")).status, "NOT_AUTHORIZED");
});

test("WebDriver mapper uses bounded W3C calls and rejects insecure non-local endpoints", async () => {
  assert.throws(() => new WebDriverPlatformBrowserMapper("http://browser.example/wd/hub"), /地址配置无效/);
  const calls = [];
  const mapper = new WebDriverPlatformBrowserMapper("https://browser.example/wd/hub", async (input, init = {}) => {
    const url = new URL(input);
    calls.push({ path: url.pathname, method: init.method, body: init.body ? JSON.parse(init.body) : null });
    if (url.pathname.endsWith("/session") && init.method === "POST") {
      return Response.json({ value: { sessionId: "webdriver-1" } });
    }
    if (url.pathname.endsWith("/url")) return Response.json({ value: null });
    if (url.pathname.endsWith("/element") && init.method === "POST") {
      return Response.json({ value: { "element-6066-11e4-a52e-4f735466cecf": "qr-image" } });
    }
    if (url.pathname.endsWith("/attribute/src")) return Response.json({ value: "data:image/png;base64,MTIz" });
    if (url.pathname.endsWith("/cookie")) return Response.json({ value: [{ name: "sessionid", value: "safe" }] });
    return Response.json({ value: null });
  });

  const started = await mapper.start("juejin");
  assert.deepEqual(started, { sessionId: "webdriver-1", qrCodeDataUrl: "data:image/png;base64,MTIz" });
  assert.deepEqual(await mapper.cookies(started.sessionId), [{ name: "sessionid", value: "safe" }]);
  await mapper.close(started.sessionId);
  assert.equal(calls[0].path, "/wd/hub/session");
  assert.equal(calls[1].body.url, "https://juejin.cn/editor/drafts/new?v=2");
  assert.equal(calls.at(-1).method, "DELETE");
});
