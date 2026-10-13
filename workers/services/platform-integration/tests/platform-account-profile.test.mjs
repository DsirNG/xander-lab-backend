import assert from "node:assert/strict";
import test from "node:test";
import { readFile } from "node:fs/promises";
import { DatabaseSync } from "node:sqlite";
import { D1PlatformCredentialMapper } from "../.test-build/mappers/d1-platform-credential-mapper.js";
import { PlatformCredentialService } from "../.test-build/services/platform-credential-service.js";
import { PlatformConnectionService } from "../.test-build/services/platform-connection-service.js";

async function fixture(context) {
  const db = new DatabaseSync(":memory:");
  context.after(() => db.close());
  for (const name of ["0001_platform_credentials.sql", "0007_expand_platform_credential_platforms.sql", "0008_platform_account_profile.sql"]) {
    db.exec(await readFile(new URL(`../migrations/${name}`, import.meta.url), "utf8"));
  }
  const binding = { prepare(sql) {
    const statement = db.prepare(sql);
    return { bind(...values) {
      return {
        async first() { return statement.get(...values) ?? null; },
        async all() { return { results: statement.all(...values) }; },
        async run() { const result = statement.run(...values); return { success: true, meta: { changes: Number(result.changes) } }; },
      };
    } };
  } };
  const mapper = new D1PlatformCredentialMapper(binding);
  const credentials = new PlatformCredentialService(mapper, Buffer.alloc(32, 7).toString("base64"));
  return { db, mapper, credentials };
}

test("verified account metadata survives reload and never changes the authorization time", async (context) => {
  const { credentials } = await fixture(context);
  await credentials.save(42, "juejin", { cookie: "sessionid=private" }, null);
  const before = await credentials.status(42, "juejin");
  const profile = { username: "1234567890123456789", nickname: "Alice", avatarUrl: "https://avatar.example/a.png" };
  const service = new PlatformConnectionService(credentials, { async getCurrentAccount() { return profile; } });
  const result = await service.testConnection(42, "juejin");
  const loaded = await credentials.status(42, "juejin");
  assert.equal(loaded.authorizedAt, before.authorizedAt);
  assert.equal(result.authorizedAt, before.authorizedAt);
  assert.deepEqual(loaded.profile, { ...profile, verifiedAt: result.verifiedAt });
  assert.equal((await credentials.status(43, "juejin")).profile, null);
  assert.ok(!JSON.stringify(loaded).includes("private"));
  await credentials.save(42, "juejin", { cookie: "sessionid=new" }, null);
  assert.equal((await credentials.status(42, "juejin")).profile, null);
});

test("extension WeChat profile is visible to the credential owner and rejects arbitrary avatar hosts", async (context) => {
  const { credentials } = await fixture(context);
  await credentials.save(42, "wechat_mp", { cookie: "slave_user=private" }, "公众号名称", {
    username: null, nickname: "公众号名称", avatarUrl: "https://wx.qlogo.cn/mmhead/a/0",
  });
  const status = await credentials.status(42, "wechat_mp");
  assert.equal(status.profile.nickname, "公众号名称");
  assert.equal(status.profile.avatarUrl, "https://wx.qlogo.cn/mmhead/a/0");
  assert.equal((await credentials.status(43, "wechat_mp")).profile, null);
  assert.ok(!JSON.stringify(status).includes("private"));

  await credentials.save(42, "wechat_mp", { cookie: "slave_user=next" }, "新公众号", {
    username: null, nickname: "新公众号", avatarUrl: "https://attacker.example/avatar.png",
  });
  assert.equal((await credentials.status(42, "wechat_mp")).profile.avatarUrl, null);
});

test("an in-flight verification cannot replace a reauthorized account or resurrect a disconnected account", async (context) => {
  const { credentials } = await fixture(context);
  const profile = { username: "old-user", nickname: "Old", avatarUrl: null };
  for (const action of [
    () => credentials.save(42, "csdn", { cookie: "UserToken=new" }, null),
    () => credentials.delete(42, "csdn"),
  ]) {
    await credentials.save(42, "csdn", { cookie: "UserToken=old" }, null);
    const service = new PlatformConnectionService(credentials, { async getCurrentAccount() { await action(); return profile; } });
    await assert.rejects(service.testConnection(42, "csdn"), (error) => error.status === 409);
    assert.equal((await credentials.status(42, "csdn")).profile, null);
  }
});
