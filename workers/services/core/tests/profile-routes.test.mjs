import assert from "node:assert/strict";
import test, { mock } from "node:test";
import coreWorker from "../.test-build/index.js";
import { signJwtToken } from "../.test-build/auth.js";

const secret = "core-profile-route-test-secret-at-least-32-bytes";
const serviceToken = "core-profile-route-service-token";

function environment(overrides = {}) {
  const user = {
    id: 42, username: "profile-user", nickname: "Profile User", email: "profile@example.com",
    avatar: null, openid: null, role: "USER", tier: "FREE", tierExpiresAt: null,
    status: 1, createdAt: "2026-10-10T00:00:00.000Z", authVersion: 0,
  };
  return {
    INTERNAL_SERVICE_TOKEN: serviceToken,
    JWT_SECRET: secret,
    DB: {
      prepare(query) {
        let values = [];
        const sql = query.replace(/\s+/g, " ").trim();
        const statement = {
          bind(...bound) { values = bound; return statement; },
          async first() {
            if (sql.includes("FROM sys_user WHERE id = ? AND NOT EXISTS")) return values[0] === 42 ? user : null;
            if (sql.includes("SELECT balance FROM sys_user_points WHERE user_id = ?")) return { balance: 1250 };
            throw new Error(`Unexpected query: ${sql}`);
          },
        };
        return statement;
      },
    },
    ...overrides,
  };
}

async function request(path, method = "GET") {
  const token = await signJwtToken("42", secret, { role: "USER", ver: 0, type: "access" }, 600);
  return new Request(`https://core.test${path}`, {
    method,
    headers: { authorization: `Bearer ${token}`, "x-dinqor-service-token": serviceToken },
  });
}

test("profile overview combines Core identity/balance with the protected Content count", async () => {
  const env = environment({
    CONTENT_AUTOMATION_SERVICE_URL: "https://content.example.test",
    CONTENT_AUTOMATION_SERVICE_TOKEN: "core-to-content-token",
  });
  const upstream = mock.method(globalThis, "fetch", async (input, init) => {
    assert.equal(String(input), "https://content.example.test/internal/content/profile/managed-count");
    assert.equal(init.method, "POST");
    assert.equal(init.headers["x-dinqor-service-token"], "core-to-content-token");
    assert.deepEqual(JSON.parse(init.body), { userId: 42 });
    return new Response(JSON.stringify({ count: 7 }), { status: 200 });
  });
  try {
    const response = await coreWorker.fetch(await request("/api/profile/overview"), env);
    assert.equal(response.status, 200);
    assert.deepEqual((await response.json()).data, {
      user: {
        username: "profile-user", nickname: "Profile User", avatar: null, role: "USER",
        tier: "FREE", tierExpiresAt: null, email: "profile@example.com", createdAt: "2026-10-10T00:00:00.000Z",
      },
      points: 1250,
      blogCount: 7,
    });
    assert.equal(upstream.mock.callCount(), 1);
  } finally {
    upstream.mock.restore();
  }
});

test("admin diagnostic broadcast calls Content only for a verified administrator", async () => {
  const env = environment({
    CONTENT_AUTOMATION_SERVICE_URL: "https://content.example.test",
    CONTENT_AUTOMATION_SERVICE_TOKEN: "core-to-content-token",
  });
  const upstream = mock.method(globalThis, "fetch", async (input, init) => {
    assert.equal(String(input), "https://content.example.test/internal/content/notifications/test-broadcast");
    assert.equal(init.method, "POST");
    return new Response(JSON.stringify({ recipients: 2 }), { status: 200 });
  });
  const adminEnv = {
    ...env,
    DB: {
      ...env.DB,
      prepare(query) {
        const statement = env.DB.prepare(query);
        const first = statement.first;
        statement.first = async (...args) => {
          const row = await first.apply(statement, args);
          return row && query.includes("FROM sys_user WHERE id = ? AND NOT EXISTS") ? { ...row, role: "ADMIN" } : row;
        };
        return statement;
      },
    },
  };
  try {
    const response = await coreWorker.fetch(await request("/api/admin/notifications/test-broadcast", "PATCH"), adminEnv);
    assert.equal(response.status, 200);
    assert.equal((await response.json()).data, 2);
    assert.equal(upstream.mock.callCount(), 1);
  } finally {
    upstream.mock.restore();
  }
});

test("profile overview fails closed when Content is unavailable", async () => {
  const response = await coreWorker.fetch(await request("/api/profile/overview"), environment());
  assert.equal(response.status, 503);
});
