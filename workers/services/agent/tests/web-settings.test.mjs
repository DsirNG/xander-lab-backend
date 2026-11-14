import assert from "node:assert/strict";
import test from "node:test";
import { AgentWebSettingsService } from "../.test-build/services/agent-web-settings-service.js";
import { AgentWebResearchService } from "../.test-build/services/agent-web-research-service.js";
import { handleAgentWebSettingsRequest } from "../.test-build/controllers/agent-web-settings-controller.js";
import { DatabaseSync } from "node:sqlite";
import { readFileSync } from "node:fs";
import { D1AgentWebSettingsMapper } from "../.test-build/mappers/d1-agent-web-settings-mapper.js";

function fixture(env = {}) {
  let row = null;
  let probes = 0;
  const repository = { async find() { return row; }, async save(input) { row = { ...input, updatedAt: "2026-11-14T01:00:00Z" }; } };
  const service = new AgentWebSettingsService(repository, { AGENT_MCP_CREDENTIAL_ENCRYPTION_KEY: Buffer.alloc(32, 7).toString("base64"), ...env }, key => ({
    async search(input) { probes++; assert.equal(key, "tvly-test-key"); assert.equal(input.limit, 1); return []; },
    async read() { throw new Error("unused"); },
  }));
  return { service, repository, get row() { return row; }, get probes() { return probes; } };
}

test("admin settings encrypt API keys, keep empty updates, replace/remove explicitly and never return secrets", async () => {
  const f = fixture();
  assert.equal((await f.service.view()).apiKeyConfigured, false);
  await assert.rejects(f.service.save({ enabled: true }, 42), { status: 400 });
  const saved = await f.service.save({ enabled: true, apiKey: "tvly-test-key" }, 42);
  assert.ok(f.row.encryptedApiKey.startsWith("v1."));
  assert.ok(!JSON.stringify(f.row).includes("tvly-test-key"));
  assert.ok(!JSON.stringify(saved).includes("tvly-test-key"));
  assert.equal(saved.apiKeyConfigured, true);
  assert.equal(saved.source, "admin");
  assert.equal((await f.service.runtime()).apiKey, "tvly-test-key");
  const original = f.row.encryptedApiKey;
  await f.service.save({ enabled: false, apiKey: "" }, 43);
  assert.equal(f.row.encryptedApiKey, original);
  assert.equal((await f.service.runtime()).apiKey, undefined);
  assert.equal((await f.service.runtime()).allowNativeSearch, false);
  await f.service.save({ enabled: true, apiKey: "tvly-replacement" }, 43);
  assert.notEqual(f.row.encryptedApiKey, original);
  assert.equal((await f.service.runtime()).apiKey, "tvly-replacement");
  await f.service.save({ enabled: false, clearApiKey: true }, 43);
  assert.equal(f.row.encryptedApiKey, null);
  assert.equal((await f.service.view()).apiKeyConfigured, false);
});

test("explicit admin disable/removal overrides environment credentials and corrupted keys fail closed", async () => {
  const f = fixture({ TAVILY_API_KEY: "tvly-test-key" });
  assert.equal((await f.service.runtime()).apiKey, "tvly-test-key");
  await f.service.save({ enabled: false }, 42);
  assert.equal((await f.service.runtime()).apiKey, undefined);
  await f.service.save({ enabled: false, clearApiKey: true }, 42);
  assert.equal((await f.service.runtime()).allowNativeSearch, false);
  await f.service.save({ enabled: true, apiKey: "tvly-test-key" }, 42);
  f.row.encryptedApiKey += "corruption";
  await assert.rejects(f.service.runtime(), { status: 503 });
  const research = new AgentWebResearchService(undefined, async () => {
    await f.service.runtime(); throw new Error("unreachable");
  });
  await research.initialize();
  assert.equal(research.available, false);
  assert.equal(research.nativeSearchAllowed, false);
  assert.deepEqual(await fixture({ AGENT_WEB_SEARCH_ENABLED: "false", TAVILY_API_KEY: "tvly-test-key" }).service.runtime(), { allowNativeSearch: false });
});

test("missing encryption configuration cannot write plaintext and probes use saved credentials only", async () => {
  const bad = fixture({ AGENT_MCP_CREDENTIAL_ENCRYPTION_KEY: "bad" });
  assert.equal((await bad.service.view()).encryptionReady, false);
  await assert.rejects(bad.service.save({ enabled: true, apiKey: "tvly-test-key" }, 42), { status: 503 });
  assert.equal(bad.row, null);
  const f = fixture();
  await assert.rejects(f.service.probe(new AbortController().signal), { status: 400 });
  await f.service.save({ enabled: true, apiKey: "tvly-test-key" }, 42);
  const result = await f.service.probe(new AbortController().signal);
  assert.equal(result.ok, true);
  assert.equal(f.probes, 1);
  assert.ok(!JSON.stringify(result).includes("tvly-test-key"));
});

test("search admin controller enforces auth before reading settings and bounds input", async () => {
  const f = fixture();
  const make = (method = "GET", body, path = "") => new Request(`https://agent.test/api/admin/agent-web-search${path}`, {
    method, ...(body === undefined ? {} : { body: typeof body === "string" ? body : JSON.stringify(body) }),
  });
  for (const status of [401, 403]) {
    const response = await handleAgentWebSettingsRequest(make("PUT", { enabled: true, apiKey: "tvly-test-key" }), f.service,
      { async currentAdminUserId() { return Response.json({ code: status, data: null }, { status }); } });
    assert.equal(response.status, status);
    assert.equal(f.row, null);
  }
  const auth = { async currentAdminUserId() { return 42; } };
  const saved = await handleAgentWebSettingsRequest(make("PUT", { enabled: true, apiKey: "tvly-test-key" }), f.service, auth);
  assert.equal(saved.status, 200);
  assert.ok(!JSON.stringify(await saved.json()).includes("tvly-test-key"));
  assert.equal((await handleAgentWebSettingsRequest(make("PUT", "x".repeat(4100)), f.service, auth)).status, 413);
  assert.equal((await handleAgentWebSettingsRequest(make("PUT", "{"), f.service, auth)).status, 400);
  assert.equal((await handleAgentWebSettingsRequest(make("DELETE"), f.service, auth)).status, 405);
  assert.equal((await handleAgentWebSettingsRequest(make("POST", {}, "/test"), f.service, auth)).status, 200);
});

test("D1 migration and mapper persist an encrypted singleton without touching other services", async () => {
  const db = new DatabaseSync(":memory:");
  try {
    db.exec(readFileSync(new URL("../migrations/0012_agent_web_settings.sql", import.meta.url), "utf8"));
    const binding = { prepare(sql) {
      const statement = db.prepare(sql);
      return { async first() { return statement.get() ?? null; }, bind(...args) { return { async run() { return statement.run(...args); } }; } };
    } };
    const mapper = new D1AgentWebSettingsMapper(binding);
    assert.equal(await mapper.find(), null);
    await mapper.save({ enabled: true, encryptedApiKey: "v1.ciphertext", updatedBy: 42 });
    await mapper.save({ enabled: false, encryptedApiKey: "v1.ciphertext", updatedBy: 43 });
    assert.equal((await mapper.find()).enabled, false);
    assert.equal(db.prepare("SELECT count(*) AS total FROM agent_web_settings").get().total, 1);
    assert.equal(db.prepare("SELECT updated_by FROM agent_web_settings").get().updated_by, 43);
  } finally { db.close(); }
});
