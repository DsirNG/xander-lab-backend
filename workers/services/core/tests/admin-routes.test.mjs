import assert from "node:assert/strict";
import test from "node:test";
import coreWorker from "../.test-build/index.js";
import { signJwtToken } from "../.test-build/auth.js";
import { decryptSecret } from "../.test-build/admin.js";

const jwtSecret = "core-admin-test-secret-with-at-least-32-bytes";
const serviceToken = "core-admin-test-service-token";
const encryptionKey = Buffer.alloc(32, 7).toString("base64");

function createDb(role = "ADMIN") {
  const providers = new Map();
  const configs = new Map();
  const state = { wechat: null, virtualPay: null, nextProviderId: 1 };
  return {
    providers,
    configs,
    state,
    prepare(query) {
      let values = [];
      const sql = query.replace(/\s+/g, " ").trim();
      const statement = {
        bind(...bound) { values = bound; return statement; },
        async first() {
          if (sql.includes("FROM sys_user WHERE id = ? AND NOT EXISTS")) {
            return values[0] === 42 ? {
              id: 42, username: "admin", nickname: "Admin", email: "admin@example.com", avatar: null,
              openid: null, role, tier: "FREE", tierExpiresAt: null, status: 1,
              createdAt: "2026-10-10T00:00:00.000Z", authVersion: 0,
            } : null;
          }
          if (sql.startsWith("SELECT id FROM model_provider WHERE name = ? COLLATE NOCASE")) {
            const row = [...providers.values()].find((provider) => provider.name.toLowerCase() === String(values[0]).toLowerCase());
            return row ? { id: row.id } : null;
          }
          if (sql.startsWith("SELECT id FROM model_provider WHERE name = ? COLLATE NOCASE AND id <> ?")) {
            const row = [...providers.values()].find((provider) => provider.id !== values[1]
              && provider.name.toLowerCase() === String(values[0]).toLowerCase());
            return row ? { id: row.id } : null;
          }
          if (sql.includes("FROM model_provider WHERE id = ?")) {
            return providers.get(Number(values[0])) ?? null;
          }
          if (sql.includes("FROM model_provider WHERE id = ? AND enabled = 1 LIMIT 1")) {
            const provider = providers.get(Number(values[0]));
            return provider?.enabled === 1 ? provider : null;
          }
          if (sql.includes("FROM feature_model_config WHERE feature_key = 'agent'")) {
            return configs.get("agent") ?? null;
          }
          if (sql.includes("INSERT INTO model_provider")) {
            const [name, vendor, baseUrl, encryptedApiKey, description, enabled, priority] = values;
            const row = {
              id: state.nextProviderId++, name, vendor, baseUrl, encryptedApiKey, description,
              enabled, priority, createdAt: "2026-10-10T00:00:00.000Z", updatedAt: "2026-10-10T00:00:00.000Z",
            };
            providers.set(row.id, row);
            return { ...row };
          }
          if (sql.includes("FROM feature_model_config WHERE feature_key = ?")) return configs.get(values[0]) ?? null;
          if (sql.includes("SELECT id FROM model_provider WHERE id = ? AND enabled = 1")) {
            const provider = providers.get(Number(values[0]));
            return provider?.enabled === 1 ? { id: provider.id } : null;
          }
          if (sql.includes("FROM wechat_credential WHERE id = 1")) return state.wechat;
          if (sql.startsWith("INSERT INTO wechat_credential")) {
            state.wechat = { appId: values[0], encrypted: values[1], updatedAt: "2026-10-10T00:00:00.000Z" };
            return state.wechat;
          }
          if (sql.includes("FROM virtual_pay_settings WHERE id = 1")) return state.virtualPay;
          throw new Error(`Unexpected first query: ${sql}`);
        },
        async all() {
          if (sql.startsWith("SELECT id, name, vendor, base_url")) {
            return { results: [...providers.values()].sort((a, b) => a.priority - b.priority || a.id - b.id) };
          }
          if (sql.startsWith("SELECT feature_key AS featureKey")) return { results: [...configs.values()] };
          throw new Error(`Unexpected all query: ${sql}`);
        },
        async run() {
          if (sql.startsWith("INSERT INTO feature_model_config")) {
            const [featureKey, enabled, primaryProviderId, primaryModel, primaryApiStyle,
              fallbackProviderId, fallbackModel, fallbackApiStyle] = values;
            configs.set(featureKey, {
              featureKey, enabled, primaryProviderId, primaryModel, primaryApiStyle,
              fallbackProviderId, fallbackModel, fallbackApiStyle, updatedAt: "2026-10-10T00:00:00.000Z",
            });
            return { success: true, meta: { changes: 1 } };
          }
          if (sql.startsWith("INSERT INTO wechat_credential")) {
            state.wechat = { appId: values[0], encrypted: values[1], updatedAt: "2026-10-10T00:00:00.000Z" };
            return { success: true, meta: { changes: 1 } };
          }
          if (sql.startsWith("DELETE FROM wechat_credential")) {
            state.wechat = null;
            return { success: true, meta: { changes: 1 } };
          }
          if (sql.startsWith("INSERT INTO virtual_pay_settings")) {
            const [enabled, offerId, encryptedAppKey, plusProductId, plusPriceCents, plusDurationDays,
              proProductId, proPriceCents, proDurationDays, ultraProductId, ultraPriceCents, ultraDurationDays] = values;
            state.virtualPay = {
              enabled, offerId, encryptedAppKey, plusProductId, plusPriceCents, plusDurationDays,
              proProductId, proPriceCents, proDurationDays, ultraProductId, ultraPriceCents, ultraDurationDays,
              updatedAt: "2026-10-10T00:00:00.000Z",
            };
            return { success: true, meta: { changes: 1 } };
          }
          if (sql.startsWith("UPDATE model_provider SET enabled")) {
            const row = providers.get(Number(values[1]));
            if (row) row.enabled = values[0];
            return { success: true, meta: { changes: row ? 1 : 0 } };
          }
          throw new Error(`Unexpected run query: ${sql}`);
        },
      };
      return statement;
    },
  };
}

function env(role = "ADMIN", overrides = {}) {
  return {
    DB: createDb(role), INTERNAL_SERVICE_TOKEN: serviceToken, JWT_SECRET: jwtSecret,
    ADMIN_MODEL_GATEWAY_ENABLED: "true", MODEL_PROVIDER_ENCRYPTION_KEY: encryptionKey,
    WECHAT_CREDENTIAL_ENCRYPTION_KEY: encryptionKey, ...overrides,
  };
}

async function request(path, options = {}) {
  const accessToken = await signJwtToken("42", jwtSecret, { role: options.role ?? "ADMIN", ver: 0, type: "access" }, 600);
  const headers = { "x-dinqor-service-token": serviceToken, authorization: `Bearer ${accessToken}` };
  if (options.body !== undefined) headers["content-type"] = "application/json";
  return new Request(`https://core.test${path}`, {
    method: options.method ?? "GET", headers,
    ...(options.body === undefined ? {} : { body: JSON.stringify(options.body) }),
  });
}

test("Core admin settings reject non-admin users and keep service authentication", async () => {
  const userEnv = env("USER");
  const denied = await coreWorker.fetch(await request("/api/admin/model-providers"), userEnv);
  assert.equal(denied.status, 403);
  const missingServiceAuth = await coreWorker.fetch(new Request("https://core.test/api/admin/model-providers"), userEnv);
  assert.equal(missingServiceAuth.status, 401);
});

test("model providers encrypt keys and never return plaintext or ciphertext", async () => {
  const environment = env();
  const created = await coreWorker.fetch(await request("/api/admin/model-providers", {
    method: "POST", body: {
      name: "Primary", vendor: "openai_compatible", baseUrl: "https://models.example/v1",
      apiKey: "secret-model-key", priority: 4,
    },
  }), environment);
  assert.equal(created.status, 200);
  const payload = await created.json();
  assert.equal(payload.data.apiKeyConfigured, true);
  assert.equal("apiKey" in payload.data, false);
  assert.equal("encryptedApiKey" in payload.data, false);
  const stored = environment.DB.providers.get(payload.data.id);
  assert.notEqual(stored.encryptedApiKey, "secret-model-key");
  assert.equal(await decryptSecret(stored.encryptedApiKey, encryptionKey), "secret-model-key");
});

test("feature model settings return stable defaults and enforce slot/provider rules", async () => {
  const environment = env();
  const list = await coreWorker.fetch(await request("/api/admin/feature-model-configs"), environment);
  const defaults = (await list.json()).data;
  assert.deepEqual(defaults.map((item) => item.featureKey), [
    "agent", "blog_agent", "blog_agent_image", "img2three_vision", "recitation_asr",
  ]);
  assert.equal(defaults.every((item) => item.enabled === false), true);

  environment.DB.providers.set(1, {
    id: 1, name: "Primary", vendor: "OPENAI_COMPATIBLE", baseUrl: "https://models.example/v1",
    encryptedApiKey: "encrypted", description: null, enabled: 1, priority: 0,
  });
  const incomplete = await coreWorker.fetch(await request("/api/admin/feature-model-configs/agent", {
    method: "PUT", body: { enabled: true, primaryProviderId: 1, primaryModel: "gpt-test" },
  }), environment);
  assert.equal(incomplete.status, 400);

  const duplicateSlots = await coreWorker.fetch(await request("/api/admin/feature-model-configs/agent", {
    method: "PUT", body: {
      enabled: true, primaryProviderId: 1, primaryModel: "gpt-test", primaryApiStyle: "CHAT_COMPLETIONS",
      fallbackProviderId: 1, fallbackModel: "gpt-test", fallbackApiStyle: "CHAT_COMPLETIONS",
    },
  }), environment);
  assert.equal(duplicateSlots.status, 400);

  const saved = await coreWorker.fetch(await request("/api/admin/feature-model-configs/agent", {
    method: "PUT", body: {
      enabled: true, primaryProviderId: 1, primaryModel: "gpt-test", primaryApiStyle: "CHAT_COMPLETIONS",
    },
  }), environment);
  assert.equal(saved.status, 200);
  assert.equal((await saved.json()).data.primaryModel, "gpt-test");
});

test("WeChat and virtual-pay settings encrypt credentials and return configured flags only", async () => {
  const environment = env();
  const savedWechat = await coreWorker.fetch(await request("/api/admin/wechat-credential", {
    method: "PUT", body: { appId: "mini-app-id", appSecret: "mini-app-secret" },
  }), environment);
  assert.equal(savedWechat.status, 200);
  const wechatPayload = (await savedWechat.json()).data;
  assert.equal(wechatPayload.secretConfigured, true);
  assert.equal("appSecret" in wechatPayload, false);
  assert.equal(await decryptSecret(environment.DB.state.wechat.encrypted, encryptionKey), "mini-app-secret");

  const initialPay = await coreWorker.fetch(await request("/api/admin/virtual-pay-settings"), environment);
  assert.deepEqual((await initialPay.json()).data.plus, { productId: "", priceCents: 0, durationDays: 30 });
  const savedPay = await coreWorker.fetch(await request("/api/admin/virtual-pay-settings", {
    method: "PUT", body: {
      enabled: true, offerId: "offer", appKey: "virtual-pay-secret",
      plus: { productId: "plus", priceCents: 100, durationDays: 30 },
      pro: { productId: "pro", priceCents: 200, durationDays: 30 },
      ultra: { productId: "ultra", priceCents: 300, durationDays: 30 },
    },
  }), environment);
  assert.equal(savedPay.status, 200);
  const payPayload = (await savedPay.json()).data;
  assert.equal(payPayload.appKeyConfigured, true);
  assert.equal("appKey" in payPayload, false);
  assert.equal(await decryptSecret(environment.DB.state.virtualPay.encryptedAppKey, encryptionKey), "virtual-pay-secret");
});

test("model gateway admin APIs remain disabled unless explicitly enabled", async () => {
  const environment = env("ADMIN", { ADMIN_MODEL_GATEWAY_ENABLED: "false" });
  const response = await coreWorker.fetch(await request("/api/admin/model-providers"), environment);
  assert.equal(response.status, 503);
});

test("Agent receives decrypted model candidates only through the protected Core contract", async () => {
  const environment = env();
  environment.DB.providers.set(1, {
    id: 1, name: "Primary", vendor: "OPENAI_COMPATIBLE", baseUrl: "https://models.example/v1",
    encryptedApiKey: null, description: null, enabled: 1, priority: 0,
  });
  const encrypted = await coreWorker.fetch(await request("/api/admin/model-providers", {
    method: "POST", body: {
      name: "Agent provider", vendor: "openai_compatible", baseUrl: "https://models.example/v1",
      apiKey: "agent-model-secret",
    },
  }), environment);
  assert.equal(encrypted.status, 200);
  const providerId = (await encrypted.json()).data.id;
  environment.DB.configs.set("agent", {
    enabled: 1, primaryProviderId: providerId, primaryModel: "test-model",
    primaryApiStyle: "CHAT_COMPLETIONS", fallbackProviderId: null, fallbackModel: null,
    fallbackApiStyle: null,
  });

  const blocked = await coreWorker.fetch(new Request("https://core.test/internal/agent/model-config", {
    method: "POST", headers: { "content-type": "application/json" }, body: "{}",
  }), environment);
  assert.equal(blocked.status, 401);

  const response = await coreWorker.fetch(new Request("https://core.test/internal/agent/model-config", {
    method: "POST", headers: {
      "content-type": "application/json", "x-dinqor-service-token": serviceToken,
    }, body: "{}",
  }), environment);
  assert.equal(response.status, 200);
  const payload = await response.json();
  assert.equal(payload.managed, true);
  assert.deepEqual(payload.candidates, [{
    providerId, baseUrl: "https://models.example/v1", apiKey: "agent-model-secret",
    model: "test-model", apiStyle: "CHAT_COMPLETIONS",
  }]);

  environment.DB.configs.set("blog_agent_image", {
    enabled: 1, primaryProviderId: providerId, primaryModel: "image-model",
    primaryApiStyle: "IMAGES_GENERATIONS", fallbackProviderId: null, fallbackModel: null,
    fallbackApiStyle: null,
  });
  const imageResponse = await coreWorker.fetch(new Request("https://core.test/internal/agent/image-model-config", {
    method: "POST", headers: {
      "content-type": "application/json", "x-dinqor-service-token": serviceToken,
    }, body: "{}",
  }), environment);
  assert.equal(imageResponse.status, 200);
  const imagePayload = await imageResponse.json();
  assert.equal(imagePayload.managed, true);
  assert.deepEqual(imagePayload.candidates, [{
    providerId, baseUrl: "https://models.example/v1", apiKey: "agent-model-secret",
    model: "image-model", apiStyle: "IMAGES_GENERATIONS",
  }]);
});
