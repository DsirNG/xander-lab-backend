import assert from "node:assert/strict";
import test from "node:test";
import integrationWorker from "../.test-build/index.js";
import { D1PlatformCredentialMapper } from "../.test-build/mappers/d1-platform-credential-mapper.js";
import { PlatformCredentialService } from "../.test-build/services/platform-credential-service.js";

const token = "platform-integration-test-token";
const key = Buffer.alloc(32, 7).toString("base64");

function d1Fixture() {
  const rows = new Map();
  return {
    rows,
    prepare(sql) {
      let values = [];
      return {
        bind(...args) { values = args; return this; },
        async first() {
          const row = rows.get(`${values[0]}:${values[1]}`);
          return row ? { ...row } : null;
        },
        async all() {
          return { results: [...rows.values()].filter((row) => row.userId === values[0]).map((row) => ({ ...row })) };
        },
        async run() {
          const rowKey = `${values[0]}:${values[1]}`;
          if (sql.includes("INSERT INTO platform_credential")) {
            rows.set(rowKey, {
              userId: values[0], platform: values[1], encryptedCredential: values[2],
              accountName: values[3], status: "ACTIVE",
            });
            return { success: true, meta: { changes: 1 } };
          }
          if (sql.includes("DELETE FROM platform_credential")) {
            const changes = rows.delete(rowKey) ? 1 : 0;
            return { success: true, meta: { changes } };
          }
          if (sql.includes("UPDATE platform_credential")) {
            const row = rows.get(rowKey);
            if (row?.status === "ACTIVE") row.status = "EXPIRED";
            return { success: true, meta: { changes: row ? 1 : 0 } };
          }
          if (sql.includes("platform_authorization") || sql.includes("platform_extension_authorization")) {
            return { success: true, meta: { changes: 0 } };
          }
          throw new Error(`unexpected SQL: ${sql}`);
        },
      };
    },
    async batch(statements) { return Promise.all(statements.map((statement) => statement.run())); },
  };
}

function workerRequest(path, { method = "GET", body, userId, serviceToken = token } = {}) {
  return new Request(`https://integration.test${path}`, {
    method,
    headers: {
      ...(serviceToken ? { "x-dinqor-service-token": serviceToken } : {}),
      ...(userId ? { "x-user-id": String(userId) } : {}),
      ...(body !== undefined ? { "content-type": "application/json" } : {}),
    },
    ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
  });
}

test("Platform credential service encrypts owner-bound provider data and scopes status", async () => {
  const db = d1Fixture();
  const repository = new D1PlatformCredentialMapper(db);
  const service = new PlatformCredentialService(repository, key);
  assert.equal((await service.status(42, "csdn")).status, "NOT_AUTHORIZED");

  await service.save(42, "csdn", { cookie: "UserToken=secret-cookie", username: "alice" }, "alice");
  await service.save(43, "juejin", { cookie: "sessionid=other-cookie" }, null);
  await service.save(42, "bilibili", { cookie: "SESSDATA=provider-session" }, null);
  assert.equal((await service.status(42, "csdn")).status, "AUTHORIZED");
  assert.equal((await service.status(43, "juejin")).status, "AUTHORIZED");
  assert.equal((await service.status(42, "juejin")).status, "NOT_AUTHORIZED");
  assert.equal((await service.status(42, "bilibili")).status, "AUTHORIZED");
  const listed = await service.statuses(42);
  assert.equal(listed.connections.csdn, "AUTHORIZED");
  assert.equal(listed.connections.bilibili, "AUTHORIZED");
  assert.doesNotMatch(db.rows.get("42:csdn").encryptedCredential, /UserToken=secret-cookie|alice/);
  assert.deepEqual(await service.require(42, "csdn"), { cookie: "UserToken=secret-cookie", username: "alice" });

  const copied = { ...db.rows.get("42:csdn"), userId: 44 };
  db.rows.set("44:csdn", copied);
  await assert.rejects(service.require(44, "csdn"), /重新授权/);
  await service.markExpired(42, "csdn");
  assert.equal((await service.status(42, "csdn")).status, "EXPIRED");
  await assert.rejects(service.require(42, "csdn"), (error) => error.status === 409);
  await assert.rejects(service.require(42, "juejin"), (error) => error.status === 409);
  await service.delete(43, "juejin");
  assert.equal((await service.status(43, "juejin")).status, "NOT_AUTHORIZED");
});

test("Platform credential endpoints enforce service and user identity and never return cookies", async () => {
  const db = d1Fixture();
  const env = {
    DB: db,
    INTERNAL_SERVICE_TOKEN: token,
    PLATFORM_CREDENTIAL_ENCRYPTION_KEY: key,
  };
  const unauthorized = await integrationWorker.fetch(workerRequest(
    "/internal/v1/platform-credentials/42/csdn", { method: "PUT", body: { credential: { cookie: "secret" }, accountName: "alice" }, serviceToken: "bad" },
  ), env);
  assert.equal(unauthorized.status, 401);

  const saved = await integrationWorker.fetch(workerRequest(
    "/internal/v1/platform-credentials/42/csdn", {
      method: "PUT", body: { credential: { cookie: "UserToken=secret-cookie", username: "alice" }, accountName: "alice" },
    },
  ), env);
  assert.equal(saved.status, 200);
  assert.doesNotMatch(await saved.text(), /UserToken=secret-cookie/);
  assert.doesNotMatch(db.rows.get("42:csdn").encryptedCredential, /UserToken=secret-cookie/);

  const missingOwner = await integrationWorker.fetch(workerRequest(
    "/api/publishing/csdn/authorization/status", { userId: undefined },
  ), env);
  assert.equal(missingOwner.status, 401);
  const ownStatus = await integrationWorker.fetch(workerRequest(
    "/api/publishing/csdn/authorization/status", { userId: 42 },
  ), env);
  const ownBody = await ownStatus.json();
  assert.equal(ownBody.data.status, "AUTHORIZED");
  assert.equal(ownBody.data.qrCodeDataUrl, null);
  assert.doesNotMatch(JSON.stringify(ownBody), /secret-cookie/);

  const connections = await integrationWorker.fetch(workerRequest("/api/platform-connections", { userId: 42 }), env);
  const connectionsBody = await connections.json();
  assert.equal(connections.status, 200);
  assert.equal(connectionsBody.data.connections.csdn, "AUTHORIZED");
  assert.equal(connectionsBody.data.connections.juejin, "NOT_AUTHORIZED");
  assert.doesNotMatch(JSON.stringify(connectionsBody), /secret-cookie|encryptedCredential/);

  const internal = await integrationWorker.fetch(workerRequest(
    "/internal/platform-integration/platform-credentials/42/csdn/status",
  ), env);
  assert.deepEqual(await internal.json(), { authorized: true });

  const removed = await integrationWorker.fetch(workerRequest(
    "/api/publishing/csdn/authorization", { method: "DELETE", userId: 42 },
  ), env);
  assert.equal(removed.status, 200);
  assert.equal((await (await integrationWorker.fetch(workerRequest(
    "/api/publishing/csdn/authorization/status", { userId: 42 },
  ), env)).json()).data.status, "NOT_AUTHORIZED");
});

test("Juejin catalog sync rejects calls without the internal service token", async () => {
  const env = { DB: d1Fixture(), INTERNAL_SERVICE_TOKEN: token, PLATFORM_CREDENTIAL_ENCRYPTION_KEY: key };
  const response = await integrationWorker.fetch(workerRequest(
    "/internal/platform-integration/platform-credentials/42/juejin/catalog/sync", {
      method: "POST", serviceToken: "incorrect-token",
    },
  ), env);
  assert.equal(response.status, 401);
});

test("Juejin article publishing rejects calls without the internal service token", async () => {
  const env = { DB: d1Fixture(), INTERNAL_SERVICE_TOKEN: token, PLATFORM_CREDENTIAL_ENCRYPTION_KEY: key };
  const response = await integrationWorker.fetch(workerRequest(
    "/internal/platform-integration/blog-posts/301/sync/juejin", {
      method: "POST", serviceToken: "incorrect-token",
      body: { userId: 42, postId: 301, post: { title: "title", content: "body" } },
    },
  ), env);
  assert.equal(response.status, 401);
});

test("Juejin publication rejects calls without the internal service token", async () => {
  const env = { DB: d1Fixture(), INTERNAL_SERVICE_TOKEN: token, PLATFORM_CREDENTIAL_ENCRYPTION_KEY: key };
  const response = await integrationWorker.fetch(workerRequest(
    "/internal/platform-integration/blog-posts/301/sync/juejin", {
      method: "POST", serviceToken: "incorrect-token",
      body: { userId: 42, postId: 301, post: { title: "title", content: "body" } },
    },
  ), env);
  assert.equal(response.status, 401);
});

test("Platform credential import rejects weak keys and malformed credential payloads", async () => {
  const db = d1Fixture();
  const service = new PlatformCredentialService(new D1PlatformCredentialMapper(db), "bad-key");
  await assert.rejects(service.save(42, "juejin", { cookie: "sessionid=secret" }, null), /密钥/);
  const valid = new PlatformCredentialService(new D1PlatformCredentialMapper(db), key);
  await assert.rejects(valid.save(42, "juejin", { cookie: "", unknown: "value" }, null), /凭证格式/);
  await assert.rejects(valid.save(42, "csdn", { cookie: "cookie", username: "alice" }, "alice"), /登录会话/);
  await assert.rejects(valid.save(42, "weibo", { cookie: "tracking=value" }, null), /登录会话/);
});

test("CSDN authorization accepts a valid session cookie without optional username cookies", async () => {
  const db = d1Fixture();
  const service = new PlatformCredentialService(new D1PlatformCredentialMapper(db), key);

  await service.save(42, "csdn", { cookie: "UserToken=provider-session" }, null);

  assert.equal((await service.status(42, "csdn")).status, "AUTHORIZED");
  assert.deepEqual(await service.require(42, "csdn"), { cookie: "UserToken=provider-session" });
});
