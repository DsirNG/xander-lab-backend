import assert from "node:assert/strict";
import test from "node:test";
import { handleInternalPaymentRequest } from "../.test-build/controllers/internal-payment-controller.js";
import { WechatCodeExchangeMapper } from "../.test-build/mappers/wechat-code-exchange-mapper.js";
import { PaymentEntitlementService } from "../.test-build/services/payment-entitlement-service.js";
import { WechatPaymentIdentityService } from "../.test-build/services/wechat-payment-identity-service.js";

test("Core resolves payment login codes only when they match an active bound account", async () => {
  const users = {
    async findById(userId) {
      if (userId === 9) return { id: 9, openid: "openid-9", status: 1 };
      if (userId === 10) return { id: 10, openid: "openid-10", status: 1 };
      return null;
    },
  };
  const credentials = {
    async resolve() { return { kind: "configured", appId: "app-id", appSecret: "app-secret" }; },
  };
  const provider = {
    async exchangePaymentCode(_appId, _secret, code) {
      return code === "good-code"
        ? { kind: "success", openid: "openid-9", sessionKey: "one-time-session-key" }
        : { kind: "success", openid: "attacker-openid", sessionKey: "wrong-session-key" };
    },
  };
  const service = new WechatPaymentIdentityService(users, credentials, provider);

  assert.deepEqual(await service.resolve(9, "good-code", {}), {
    kind: "success", openid: "openid-9", sessionKey: "one-time-session-key",
  });
  assert.deepEqual(await service.resolve(10, "good-code", {}), { kind: "identity_mismatch" });
  assert.deepEqual(await service.resolve(99, "good-code", {}), { kind: "account_disabled" });
});

test("Core private Payment contract resolves identities and grants an entitlement once", async () => {
  const users = { async findById() { return { id: 9, openid: "openid-9", status: 1 }; } };
  const credentials = {
    async resolve() { return { kind: "configured", appId: "app-id", appSecret: "app-secret" }; },
  };
  const provider = {
    async exchangePaymentCode() {
      return { kind: "success", openid: "openid-9", sessionKey: "session-key" };
    },
  };
  const identity = new WechatPaymentIdentityService(users, credentials, provider);
  const events = new Set();
  const entitlements = new PaymentEntitlementService({
    async grantOnce(_userId, _tier, _days, key) {
      const inserted = !events.has(key);
      events.add(key);
      return inserted;
    },
  });
  const deps = {
    environment: {},
    credentials,
    paymentIdentity: identity,
    entitlements,
  };

  const identityResponse = await handleInternalPaymentRequest(new Request(
    "https://core.example/internal/v1/payment/identity/resolve",
    { method: "POST", body: JSON.stringify({ userId: 9, loginCode: "once" }) },
  ), deps);
  assert.equal(identityResponse.status, 200);
  const identityBody = await identityResponse.json();
  assert.equal(identityBody.data.sessionKey, "session-key");

  const body = JSON.stringify({ userId: 9, tier: "PRO", durationDays: 30, eventKey: "virtualpay:trade-1" });
  const first = await handleInternalPaymentRequest(new Request(
    "https://core.example/internal/v1/payment/entitlements/grant", { method: "POST", body },
  ), deps);
  const second = await handleInternalPaymentRequest(new Request(
    "https://core.example/internal/v1/payment/entitlements/grant", { method: "POST", body },
  ), deps);
  assert.equal((await first.json()).data.firstGrant, true);
  assert.equal((await second.json()).data.firstGrant, false);
});

test("Core entitlement grants validate event identity and paid tier", async () => {
  const service = new PaymentEntitlementService({ async grantOnce() { return true; } });
  await assert.rejects(service.grantOnce(9, "FREE", 30, "virtualpay:trade"), /invalid/i);
  await assert.rejects(service.grantOnce(9, "PRO", 30, "trade"), /invalid/i);
});

test("Core payment identity exchange requires HTTPS WeChat and a session key", async () => {
  const mapper = new WechatCodeExchangeMapper();
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (input) => {
    const url = new URL(input);
    assert.equal(url.hostname, "api.weixin.qq.com");
    assert.equal(url.searchParams.get("js_code"), "one-time-code");
    return Response.json({ openid: "openid-1", session_key: "session-1" });
  };
  try {
    assert.deepEqual(await mapper.exchangePaymentCode(
      "app-id", "app-secret", "one-time-code",
    ), { kind: "success", openid: "openid-1", sessionKey: "session-1" });
    assert.deepEqual(await mapper.exchangePaymentCode(
      "app-id", "app-secret", "code", "http://api.weixin.qq.com/session",
    ), { kind: "invalid_configuration" });
  } finally {
    globalThis.fetch = originalFetch;
  }
});
