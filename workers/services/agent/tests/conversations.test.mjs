import assert from "node:assert/strict";
import { createHmac } from "node:crypto";
import test, { afterEach, mock } from "node:test";
import agentWorker from "../.test-build/index.js";

const serviceToken = "agent-service-test-token";
const coreServiceToken = "core-service-test-token";
const jwtSecret = "agent-test-jwt-secret-at-least-32-bytes";

afterEach(() => mock.restoreAll());

function mockCoreResolution(userId = 42) {
  mock.method(globalThis, "fetch", async (input, init) => {
    const request = input instanceof Request ? input : new Request(input, init);
    assert.equal(request.url, "https://core.test/internal/agent/auth/resolve");
    assert.equal(request.headers.get("x-dinqor-service-token"), coreServiceToken);
    const body = await request.json();
    assert.equal(typeof body.accessToken, "string");
    return Response.json({ userId });
  });
}

function encode(value) {
  return Buffer.from(JSON.stringify(value)).toString("base64url");
}

function signToken(sub = "42") {
  const header = encode({ alg: "HS256", typ: "JWT" });
  const payload = encode({ sub, exp: Math.floor(Date.now() / 1000) + 300, type: "access" });
  const content = header + "." + payload;
  return content + "." + createHmac("sha256", jwtSecret).update(content).digest("base64url");
}

function conversation(overrides = {}) {
  return {
    id: 7,
    userId: 42,
    title: "项目讨论",
    status: "ready",
    errorMessage: null,
    runVersion: 1,
    cancelRequested: 0,
    planJson: null,
    shareToken: null,
    isPinned: 0,
    lastReadAt: null,
    lastCompletedAt: null,
    createdAt: "2026-10-10T00:00:00.000Z",
    updatedAt: "2026-10-10T00:00:00.000Z",
    ...overrides,
  };
}

function createEnvironment(rows = [conversation()]) {
  const calls = [];
  const db = {
    prepare(query) {
      const call = { query, values: [] };
      calls.push(call);
      return {
        bind(...values) {
          call.values = values;
          return this;
        },
        async first() {
          if (query.includes("INSERT INTO agent_conversation")) {
            return conversation({ id: 8, userId: call.values[0], title: call.values[1] });
          }
          if (query.includes("WHERE share_token = ?")) {
            return rows.find((row) => row.shareToken === call.values[0]) ?? null;
          }
          if (query.includes("WHERE id = ? AND user_id = ?")) {
            return rows.find((row) => row.id === call.values[0]
              && row.userId === call.values[1]) ?? null;
          }
          throw new Error("Unexpected Agent D1 first query");
        },
        async all() {
          if (query.includes("FROM agent_conversation")) {
            const userRows = rows.filter((row) => row.userId === call.values[0]);
            const limit = call.values.at(-2);
            const offset = call.values.at(-1);
            return { results: userRows.slice(offset, offset + limit) };
          }
          if (query.includes("FROM agent_message")) return { results: [] };
          throw new Error("Unexpected Agent D1 all query");
        },
        async run() {
          return { success: true, meta: { changes: 1 } };
        },
      };
    },
    async batch() {
      return [];
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

function makeRequest(path, { method = "GET", token = signToken(), body } = {}) {
  const headers = { "x-dinqor-service-token": serviceToken };
  if (token) headers.authorization = `Bearer ${token}`;
  if (body !== undefined) headers["content-type"] = "application/json";
  return new Request(`https://agent.test${path}`, {
    method,
    headers,
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
}

async function readJson(response) {
  return response.json();
}

test("Agent rejects missing service and user credentials", async () => {
  const { env } = createEnvironment();
  const noService = await agentWorker.fetch(
    new Request("https://agent.test/api/agent/conversations"), env,
  );
  assert.equal(noService.status, 401);

  const noUser = await agentWorker.fetch(
    makeRequest("/api/agent/conversations", { token: null }), env,
  );
  assert.equal(noUser.status, 401);
  assert.equal((await readJson(noUser)).message, "未登录或登录已过期");
});

test("conversation creation preserves the Java response envelope and owner", async () => {
  mockCoreResolution();
  const { env, calls } = createEnvironment();
  const response = await agentWorker.fetch(
    makeRequest("/api/agent/conversations", {
      method: "POST",
      body: { content: "  你好，帮我梳理迁移步骤  " },
    }),
    env,
  );

  assert.equal(response.status, 200);
  const result = await readJson(response);
  assert.equal(result.code, 200);
  assert.equal(result.message, "success");
  assert.equal(result.data.conversation.userId, 42);
  assert.equal(result.data.conversation.title, "你好，帮我梳理迁移步骤");
  assert.deepEqual(result.data.messages, []);
  assert.deepEqual(calls.at(-1).values, [42, "你好，帮我梳理迁移步骤"]);
});

test("conversation listing is scoped to the verified token subject", async () => {
  mockCoreResolution();
  const { env, calls } = createEnvironment([
    conversation({ id: 7, userId: 42 }),
    conversation({ id: 8, userId: 99 }),
  ]);
  const response = await agentWorker.fetch(makeRequest("/api/agent/conversations"), env);
  const result = await readJson(response);

  assert.equal(response.status, 200);
  assert.deepEqual(result.data.items.map((item) => item.id), [7]);
  assert.equal(result.data.page, 1);
  assert.equal(result.data.size, 30);
  assert.equal(result.data.hasMore, false);
  assert.equal(calls[0].values[0], 42);
});

test("another user's conversation is indistinguishable from a missing one", async () => {
  mockCoreResolution();
  const { env } = createEnvironment([conversation({ userId: 99 })]);
  const response = await agentWorker.fetch(
    makeRequest("/api/agent/conversations/7"), env,
  );

  assert.equal(response.status, 404);
  assert.equal((await readJson(response)).message, "会话不存在");
});

test("conversation creation rejects empty input", async () => {
  mockCoreResolution();
  const { env } = createEnvironment();
  const response = await agentWorker.fetch(
    makeRequest("/api/agent/conversations", { method: "POST", body: {} }), env,
  );

  assert.equal(response.status, 400);
  assert.equal((await readJson(response)).message, "消息内容和附件不能同时为空");
});

test("conversation creation accepts attachment-only input like the Java controller", async () => {
  mockCoreResolution();
  const { env, calls } = createEnvironment();
  const response = await agentWorker.fetch(
    makeRequest("/api/agent/conversations", {
      method: "POST",
      body: {
        attachments: [{
          url: "https://oss.example.invalid/attachment",
          name: "notes.txt",
          contentType: "text/plain",
          size: 12,
        }],
      },
    }),
    env,
  );

  assert.equal(response.status, 200);
  const result = await readJson(response);
  assert.equal(result.data.conversation.title, "请分析我上传的附件。");
  assert.deepEqual(result.data.messages, []);
  assert.deepEqual(calls.at(-1).values, [42, "请分析我上传的附件。"]);
});

test("Agent rejects a signed token when Core reports the account disabled", async () => {
  mockCoreResolution(null);
  const { env } = createEnvironment();
  const response = await agentWorker.fetch(
    makeRequest("/api/agent/conversations"), env,
  );

  assert.equal(response.status, 401);
  assert.equal((await readJson(response)).message, "未登录或登录已过期");
});

test("shared conversation reads do not require a user token", async () => {
  const { env } = createEnvironment([conversation({ shareToken: "public-token" })]);
  const response = await agentWorker.fetch(
    makeRequest("/api/agent/conversations/shared/public-token", { token: null }), env,
  );
  const result = await readJson(response);

  assert.equal(response.status, 200);
  assert.equal(result.data.conversation.shareToken, "public-token");
  assert.deepEqual(result.data.messages, []);
});
