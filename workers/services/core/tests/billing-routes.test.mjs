import assert from "node:assert/strict";
import test from "node:test";
import coreWorker from "../.test-build/index.js";
import { signJwtToken } from "../.test-build/auth.js";

const secret = "billing-test-jwt-secret-at-least-32-bytes";
const serviceToken = "billing-test-service-token";

function createDb(role = "USER") {
  const modelPrices = new Map();
  const statements = [];
  const user = {
    id: 42,
    username: "billing-user",
    nickname: "Billing User",
    email: "billing@example.com",
    avatar: null,
    openid: null,
    role,
    tier: "FREE",
    tierExpiresAt: null,
    status: 1,
    createdAt: "2026-10-10T00:00:00.000Z",
    authVersion: 0,
  };
  return {
    statements,
    modelPrices,
    prepare(query) {
      let values = [];
      const sql = query.replace(/\s+/g, " ").trim();
      const statement = {
        bind(...bound) {
          values = bound;
          return statement;
        },
        async first() {
          statements.push({ sql, values });
          if (sql.includes("FROM sys_user WHERE id = ? AND NOT EXISTS")) {
            return values[0] === user.id && user.status === 1 ? { ...user } : null;
          }
          if (sql.includes("SELECT balance FROM sys_user_points")) return { balance: 1_500_000 };
          if (sql.includes("SELECT COALESCE(SUM(amount), 0) AS consumedToday")) return { consumedToday: 4_500 };
          if (sql.includes("SELECT COUNT(*) AS total FROM points_ledger")) return { total: 2 };
          if (sql.includes("INSERT INTO billing_model_price")) {
            const [model, input, output, cached, cachedWrite, enabled, remark] = values;
            const row = {
              id: 1, model, inputMilliPer1kToken: input, outputMilliPer1kToken: output,
              cachedMilliPer1kToken: cached, cachedWriteMilliPer1kToken: cachedWrite,
              enabled, remark, updatedAt: "2026-10-10T00:00:00.000Z",
            };
            modelPrices.set(model, row);
            return row;
          }
          if (sql.includes("FROM billing_model_price WHERE model = ?")) return modelPrices.get(values[0]) ?? null;
          throw new Error(`Unexpected first query: ${sql}`);
        },
        async all() {
          statements.push({ sql, values });
          if (sql.includes("FROM points_ledger WHERE user_id = ?")) {
            const rows = [
              { id: 8, bizType: "grant", bizKey: "user:42", bizRef: null, amount: 2_000_000,
                balanceAfter: 2_000_000, unitPrice: "50/200/5", model: null, inputTokens: null,
                outputTokens: null, cachedTokens: null, remark: "注册赠送", createdAt: "2026-10-10T00:00:00.000Z" },
              { id: 7, bizType: "consume", bizKey: "task:1", bizRef: "task-1", amount: 500,
                balanceAfter: 1_500_000, unitPrice: "50/200/5", model: null, inputTokens: null,
                outputTokens: null, cachedTokens: null, remark: null, createdAt: "2026-10-10T00:00:00.000Z" },
            ];
            const [, limit, offset] = values;
            return { results: rows.slice(offset, offset + limit) };
          }
          if (sql.includes("FROM billing_model_price")) return { results: [...modelPrices.values()] };
          throw new Error(`Unexpected all query: ${sql}`);
        },
        async run() {
          statements.push({ sql, values });
          return { success: true, meta: { changes: 1 } };
        },
      };
      return statement;
    },
    async batch() { throw new Error("Unexpected billing batch"); },
  };
}

async function token(role = "USER") {
  return signJwtToken("42", secret, { role, ver: 0, type: "access" }, 600);
}

function request(path, { method = "GET", body, accessToken, internalToken = serviceToken } = {}) {
  const headers = { "x-dinqor-service-token": internalToken };
  if (accessToken) headers.authorization = `Bearer ${accessToken}`;
  if (body !== undefined) headers["content-type"] = "application/json";
  return new Request(`https://core.test${path}`, {
    method,
    headers,
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
}

function environment(role = "USER") {
  return {
    DB: createDb(role),
    INTERNAL_SERVICE_TOKEN: serviceToken,
    JWT_SECRET: secret,
  };
}

test("points overview and ledger are private and scoped to the authenticated user", async () => {
  const env = environment();
  const accessToken = await token();
  const unauthorized = await coreWorker.fetch(request("/api/points", { internalToken: serviceToken }), env);
  assert.equal(unauthorized.status, 401);

  const overview = await coreWorker.fetch(request("/api/points", { accessToken }), env);
  assert.equal(overview.status, 200);
  assert.deepEqual((await overview.json()).data, {
    unit: "milli-point",
    balance: 1_500_000,
    consumedToday: 4_500,
    enablePreCheck: false,
  });

  const ledger = await coreWorker.fetch(request("/api/points/ledger?page=2&size=1", { accessToken }), env);
  const payload = await ledger.json();
  assert.equal(ledger.status, 200);
  assert.equal(payload.data.total, 2);
  assert.equal(payload.data.records.length, 1);
  assert.equal(payload.data.records[0].direction, "debit");
  assert.equal(payload.data.records[0].delta, -500);
  assert.deepEqual(env.DB.statements.find((item) => item.sql.includes("ORDER BY id DESC LIMIT ? OFFSET ?")).values,
    [42, 1, 1]);
});

test("model prices expose Java-compatible defaults and admin upsert requires ADMIN", async () => {
  const env = environment();
  const accessToken = await token();
  const prices = await coreWorker.fetch(request("/api/points/prices", { accessToken }), env);
  const priceData = (await prices.json()).data;
  assert.equal(priceData.unit, "milli-point");
  assert.equal(priceData.models["gpt-5.6-terra"].inputMilliPer1kToken, 20);
  assert.equal(priceData.models["gpt-image-2"].outputMilliPer1kToken, 300);

  const forbidden = await coreWorker.fetch(request("/api/admin/billing/model-prices/test-model", {
    method: "PUT", accessToken, body: { inputMilliPer1kToken: 1, outputMilliPer1kToken: 2 },
  }), env);
  assert.equal(forbidden.status, 403);

  const adminEnv = environment("ADMIN");
  const adminToken = await token("ADMIN");
  const saved = await coreWorker.fetch(request("/api/admin/billing/model-prices/test-model", {
    method: "PUT",
    accessToken: adminToken,
    body: { inputMilliPer1kToken: 3, outputMilliPer1kToken: 9, enabled: false, remark: "manual" },
  }), adminEnv);
  const savedData = (await saved.json()).data;
  assert.equal(saved.status, 200);
  assert.equal(savedData.cachedMilliPer1kToken, 0);
  assert.equal(savedData.enabled, false);
  assert.equal(savedData.remark, "manual");
});
