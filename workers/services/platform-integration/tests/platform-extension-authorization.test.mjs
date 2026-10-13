import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import test from "node:test";
import { handlePlatformCredentialRequest } from "../.test-build/controllers/platform-credential-controller.js";
import { PlatformExtensionAuthorizationService } from "../.test-build/services/platform-extension-authorization-service.js";

const ttlMs = 5 * 60 * 1000;

function fixture({ saveCredential } = {}) {
  let now = Date.UTC(2026, 9, 11, 0, 0, 0);
  const tasks = new Map();
  const saved = [];
  const deleted = [];
  const repository = {
    tasks,
    async create(task) { tasks.set(task.id, { ...task }); },
    async find(id) { return tasks.has(id) ? { ...tasks.get(id) } : null; },
    async claim(id, tokenHash, currentTime) {
      const task = tasks.get(id);
      if (!task || task.tokenHash !== tokenHash || task.status !== "PENDING" || task.expiresAt <= currentTime) return false;
      task.status = "PROCESSING";
      return true;
    },
    async complete(id) {
      const task = tasks.get(id);
      if (!task || task.status !== "PROCESSING") throw new Error("task is not processing");
      task.status = "COMPLETED";
    },
    async reopen(id) {
      const task = tasks.get(id);
      if (task?.status === "PROCESSING") task.status = "PENDING";
    },
    async cancel(id, userId) {
      const task = tasks.get(id);
      if (task?.userId === userId && task.status === "PENDING") task.status = "CANCELLED";
    },
    async cancelForPlatform(userId, platform) {
      for (const task of tasks.values()) {
        if (task.userId === userId && task.platform === platform && ["PENDING", "PROCESSING"].includes(task.status)) {
          task.status = "CANCELLED";
        }
      }
    },
    async expire(currentTime) {
      for (const task of tasks.values()) {
        if (["PENDING", "PROCESSING"].includes(task.status) && task.expiresAt <= currentTime) task.status = "EXPIRED";
      }
    },
  };
  const credentials = {
    async save(...args) {
      if (saveCredential) return saveCredential(...args);
      saved.push(args);
    },
    async delete(...args) { deleted.push(args); },
  };
  const service = new PlatformExtensionAuthorizationService(repository, credentials, () => now);
  return { service, repository, saved, deleted, advance(ms) { now += ms; } };
}

function statusIs(status) {
  return (error) => error?.status === status;
}

test("extension authorization stores only a hash and saves credentials for the task owner once", async () => {
  const value = fixture();
  const task = await value.service.start(71, "csdn");
  const row = value.repository.tasks.get(task.taskId);

  assert.match(task.transferToken, /^[A-Za-z0-9_-]{43}$/);
  assert.equal(task.expiresInSeconds, 300);
  assert.equal(Date.parse(task.expiresAt) - Date.UTC(2026, 9, 11, 0, 0, 0), ttlMs);
  assert.equal(row.tokenHash, createHash("sha256").update(task.transferToken).digest("base64url"));
  assert.equal(JSON.stringify(row).includes(task.transferToken), false);

  const credential = { cookie: "UserToken=provider-session", username: "ada" };
  await value.service.complete(task.taskId, "csdn", task.transferToken, credential, "ada");
  await value.service.complete(task.taskId, "csdn", task.transferToken, credential, "ada");

  assert.equal(row.status, "COMPLETED");
  assert.deepEqual(value.saved, [[71, "csdn", credential, "ada"]]);
});

test("WeChat MP uses the same single-use browser handoff", async () => {
  const value = fixture();
  const task = await value.service.start(72, "wechat_mp");
  const credential = { cookie: "slave_user=provider-session" };

  await value.service.complete(task.taskId, "wechat_mp", task.transferToken, credential, null);

  assert.equal(value.repository.tasks.get(task.taskId).status, "COMPLETED");
  assert.deepEqual(value.saved, [[72, "wechat_mp", credential, null]]);
});

test("disconnect cancels an in-flight handoff and removes credentials saved by its race", async () => {
  let releaseSave;
  let markSaveStarted;
  const gate = new Promise((resolve) => { releaseSave = resolve; });
  const saveStarted = new Promise((resolve) => { markSaveStarted = resolve; });
  const value = fixture({ saveCredential: async () => { markSaveStarted(); await gate; } });
  const task = await value.service.start(73, "weibo");
  const completion = value.service.complete(task.taskId, "weibo", task.transferToken, { cookie: "SUB=session" }, null);
  await saveStarted;
  await value.service.cancelForPlatform(73, "weibo");
  releaseSave();

  await assert.rejects(completion, statusIs(409));
  assert.deepEqual(value.deleted, [[73, "weibo"]]);
  assert.equal(value.repository.tasks.get(task.taskId).status, "CANCELLED");
});

test("extension authorization rejects wrong platform, bad tokens, cancelled and expired grants", async () => {
  const value = fixture();
  const wrongPlatform = await value.service.start(82, "juejin");
  await assert.rejects(
    value.service.complete(wrongPlatform.taskId, "csdn", wrongPlatform.transferToken, { cookie: "x=y" }, null),
    statusIs(404),
  );
  await assert.rejects(
    value.service.complete(wrongPlatform.taskId, "juejin", "invalid", { cookie: "x=y" }, null),
    statusIs(401),
  );

  await value.service.cancel(99, wrongPlatform.taskId);
  assert.equal(value.repository.tasks.get(wrongPlatform.taskId).status, "PENDING");
  await value.service.cancel(82, wrongPlatform.taskId);
  await assert.rejects(
    value.service.complete(wrongPlatform.taskId, "juejin", wrongPlatform.transferToken, { cookie: "x=y" }, null),
    statusIs(409),
  );

  const expired = await value.service.start(83, "csdn");
  value.advance(ttlMs);
  await assert.rejects(
    value.service.complete(expired.taskId, "csdn", expired.transferToken, { cookie: "x=y" }, "ada"),
    statusIs(410),
  );
  assert.deepEqual(value.saved, []);
});

test("extension authorization releases a claimed task when credential persistence fails", async () => {
  const value = fixture({ saveCredential: async () => { throw new Error("storage unavailable"); } });
  const task = await value.service.start(91, "juejin");

  await assert.rejects(
    value.service.complete(task.taskId, "juejin", task.transferToken, { cookie: "sessionid=x" }, null),
    /storage unavailable/,
  );
  assert.equal(value.repository.tasks.get(task.taskId).status, "PENDING");
});

test("credential controller binds extension grants to the authenticated owner and accepts the bearer handoff", async () => {
  const value = fixture();
  const start = await handlePlatformCredentialRequest(
    new Request("https://integration.test/api/publishing/csdn/authorization/start", {
      method: "POST",
      headers: { "x-user-id": "101" },
    }),
    {},
    {},
    {},
    value.service,
  );
  const started = await start.json();
  assert.equal(start.status, 200);
  assert.equal(value.repository.tasks.get(started.data.taskId).userId, 101);

  const missingOwner = await handlePlatformCredentialRequest(
    new Request("https://integration.test/api/publishing/csdn/authorization/start", { method: "POST" }),
    {}, {}, {}, value.service,
  );
  assert.equal(missingOwner.status, 401);

  const completed = await handlePlatformCredentialRequest(
    new Request(`https://integration.test/api/publishing/csdn/authorization/extension/${started.data.taskId}/complete`, {
      method: "POST",
      headers: {
        authorization: `Bearer ${started.data.transferToken}`,
        "content-type": "application/json",
      },
      body: JSON.stringify({
        credential: { cookie: "UserToken=provider-session", username: "ada" },
        accountName: "ada",
      }),
    }),
    {}, {}, {}, value.service,
  );
  assert.equal(completed.status, 200);
  assert.deepEqual((await completed.json()).data, { authorized: true });
  assert.deepEqual(value.saved, [[101, "csdn", { cookie: "UserToken=provider-session", username: "ada" }, "ada"]]);
});

test("credential controller starts a connect-only platform grant and rejects unknown platform names", async () => {
  const value = fixture();
  const start = await handlePlatformCredentialRequest(
    new Request("https://integration.test/api/publishing/bilibili/authorization/start", {
      method: "POST",
      headers: { "x-user-id": "102" },
    }),
    {}, {}, {}, value.service,
  );
  assert.equal(start.status, 200);
  const body = await start.json();
  assert.equal(value.repository.tasks.get(body.data.taskId).platform, "bilibili");

  const unknown = await handlePlatformCredentialRequest(
    new Request("https://integration.test/api/publishing/unknown/authorization/start", {
      method: "POST",
      headers: { "x-user-id": "102" },
    }),
    {}, {}, {}, value.service,
  );
  assert.equal(unknown, null);
});
