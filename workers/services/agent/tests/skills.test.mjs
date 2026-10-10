import assert from "node:assert/strict";
import { createHmac } from "node:crypto";
import test, { afterEach, mock } from "node:test";
import agentWorker from "../.test-build/index.js";

const serviceToken = "agent-skill-service-test-token";
const coreServiceToken = "core-skill-service-test-token";
const jwtSecret = "agent-skill-test-jwt-secret-at-least-32-bytes";

afterEach(() => mock.restoreAll());

function encode(value) {
  return Buffer.from(JSON.stringify(value)).toString("base64url");
}

function signToken(sub = "42") {
  const header = encode({ alg: "HS256", typ: "JWT" });
  const payload = encode({ sub, exp: Math.floor(Date.now() / 1000) + 300, type: "access" });
  const content = `${header}.${payload}`;
  return `${content}.${createHmac("sha256", jwtSecret).update(content).digest("base64url")}`;
}

function mockCoreResolution(userId = 42) {
  mock.method(globalThis, "fetch", async (input, init) => {
    const request = input instanceof Request ? input : new Request(input, init);
    assert.equal(request.url, "https://core.test/internal/agent/auth/resolve");
    assert.equal(request.headers.get("x-dinqor-service-token"), coreServiceToken);
    return Response.json({ userId });
  });
}

function makeEnvironment(rows = []) {
  const calls = [];
  const db = {
    prepare(query) {
      const call = { query, values: [] };
      calls.push(call);
      return {
        bind(...values) { call.values = values; return this; },
        async all() {
          assert.match(query, /FROM agent_skill/);
          return { results: rows.filter((row) => row.userId === call.values[0] && row.status === "ACTIVE") };
        },
        async first() {
          if (query.includes("INSERT INTO agent_skill")) {
            const row = {
              id: 19,
              userId: call.values[0],
              skillKey: call.values[1],
              version: 1,
              name: call.values[2],
              description: call.values[3],
              instructions: call.values[4],
              toolNamesJson: call.values[5],
              status: "ACTIVE",
              createdAt: "2026-10-10T00:00:00.000Z",
              updatedAt: "2026-10-10T00:00:00.000Z",
            };
            rows.push(row);
            return row;
          }
          if (query.includes("UPDATE agent_skill")) {
            const row = rows.find((item) => item.id === call.values[0]
              && item.userId === call.values[1] && item.status === "ACTIVE");
            if (!row) return null;
            row.status = "ARCHIVED";
            return { id: row.id };
          }
          throw new Error(`Unexpected Agent skill D1 query: ${query}`);
        },
      };
    },
  };
  return {
    calls,
    env: {
      DB: db,
      INTERNAL_SERVICE_TOKEN: serviceToken,
      JWT_SECRET: jwtSecret,
      CORE_SERVICE_URL: "https://core.test",
      CORE_SERVICE_TOKEN: coreServiceToken,
    },
  };
}

function makeRequest(path, { method = "GET", body } = {}) {
  const headers = {
    "x-dinqor-service-token": serviceToken,
    authorization: `Bearer ${signToken()}`,
  };
  if (body !== undefined) headers["content-type"] = "application/json";
  return new Request(`https://agent.test${path}`, {
    method,
    headers,
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
}

test("Agent skills list and tool options are authenticated and owner-scoped", async () => {
  mockCoreResolution();
  const owned = { id: 1, userId: 42, skillKey: "release-notes", version: 2, status: "ACTIVE" };
  const { env, calls } = makeEnvironment([owned, { ...owned, id: 2, userId: 99 }]);
  const list = await agentWorker.fetch(makeRequest("/api/agent/skills"), env);
  const tools = await agentWorker.fetch(makeRequest("/api/agent/skills/tools"), env);

  assert.equal(list.status, 200);
  assert.deepEqual((await list.json()).data, [owned]);
  assert.equal(calls[0].values[0], 42);
  assert.deepEqual((await tools.json()).data, [{
    name: "publish_post",
    description: "经用户审批后，将已有文章同步到其 CSDN 或掘金账号。",
  }]);
});

test("Agent skill creation stores a new version with the Java response fields", async () => {
  mockCoreResolution();
  const { env, calls } = makeEnvironment();
  const response = await agentWorker.fetch(makeRequest("/api/agent/skills", {
    method: "POST",
    body: {
      skillKey: "release-notes",
      name: "Release notes",
      description: "Summarize changes for a release.",
      instructions: "Read recent updates and write concise release notes.",
    },
  }), env);
  const result = await response.json();

  assert.equal(response.status, 200);
  assert.equal(result.data.skillKey, "release-notes");
  assert.equal(result.data.version, 1);
  assert.equal(result.data.toolNamesJson, "[]");
  assert.equal(calls[0].values[0], 42);
  assert.match(calls[0].query, /COALESCE\(MAX\(version\), 0\) \+ 1/);
});

test("Agent skill creation accepts the registered publisher and rejects unknown tools", async () => {
  mockCoreResolution();
  const { env } = makeEnvironment();
  const base = {
    skillKey: "release-notes",
    name: "Release notes",
    description: "Summarize changes.",
    instructions: "Write concise release notes.",
  };
  const malformed = await agentWorker.fetch(makeRequest("/api/agent/skills", {
    method: "POST", body: { ...base, skillKey: "Bad Key" },
  }), env);
  const registered = await agentWorker.fetch(makeRequest("/api/agent/skills", {
    method: "POST", body: { ...base, toolNames: ["publish_post"] },
  }), env);
  const unregistered = await agentWorker.fetch(makeRequest("/api/agent/skills", {
    method: "POST", body: { ...base, skillKey: "unknown-tool", toolNames: ["delete_database"] },
  }), env);

  assert.equal(malformed.status, 400);
  assert.equal(registered.status, 200);
  assert.equal((await registered.json()).data.toolNamesJson, "[\"publish_post\"]");
  assert.equal(unregistered.status, 400);
  assert.equal((await unregistered.json()).message, "Skill包含未注册工具");
});

test("Agent skill archive is scoped to its owner and only archives active rows", async () => {
  mockCoreResolution();
  const { env, calls } = makeEnvironment([{ id: 17, userId: 42, status: "ACTIVE" }]);
  const response = await agentWorker.fetch(makeRequest("/api/agent/skills/17", { method: "DELETE" }), env);
  const missing = await agentWorker.fetch(makeRequest("/api/agent/skills/18", { method: "DELETE" }), env);

  assert.equal(response.status, 200);
  assert.equal((await response.json()).data, null);
  assert.equal(missing.status, 400);
  assert.deepEqual(calls[0].values, [17, 42]);
  assert.match(calls[0].query, /user_id = \? AND status = 'ACTIVE'/);
});
