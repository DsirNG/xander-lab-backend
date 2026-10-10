import assert from "node:assert/strict";
import test from "node:test";
import { billingInternals } from "../.test-build/billing.js";

test("milli-point billing rounds token usage and caps cached tokens at input usage", () => {
  const price = {
    inputMilliPer1kToken: 50,
    outputMilliPer1kToken: 200,
    cachedMilliPer1kToken: 5,
    cachedWriteMilliPer1kToken: 0,
  };
  assert.equal(billingInternals.tokenCost({ inputTokens: 1_000, outputTokens: 1_000, cachedTokens: 250 }, price), 239);
  assert.equal(billingInternals.tokenCost({ inputTokens: 1, outputTokens: 0, cachedTokens: 20 }, price), 1);
});

test("billing defaults preserve Java prices and safely parse worker overrides", () => {
  const defaults = billingInternals.settings({});
  assert.equal(defaults.enabled, true);
  assert.equal(defaults.grantRegister, 2_000_000);
  assert.equal(defaults.preCheckMinBalance, 1_000);
  assert.deepEqual(defaults.price, {
    inputMilliPer1kToken: 50,
    outputMilliPer1kToken: 200,
    cachedMilliPer1kToken: 5,
    cachedWriteMilliPer1kToken: 0,
  });
  assert.equal(defaults.flat["email-reminder"], 5_000);

  const configured = billingInternals.settings({
    BILLING_ENABLED: "false",
    BILLING_GRANT_REGISTER: "3000000",
    BILLING_MODEL_PRICES_JSON: JSON.stringify({
      "my-model": {
        inputMilliPer1kToken: 4,
        outputMilliPer1kToken: 8,
        cachedMilliPer1kToken: 1,
        cachedWriteMilliPer1kToken: 2,
      },
      invalid: { inputMilliPer1kToken: -1, outputMilliPer1kToken: 2 },
    }),
  });
  assert.equal(configured.enabled, false);
  assert.equal(configured.grantRegister, 3_000_000);
  assert.equal(billingInternals.configuredModelPrices({ BILLING_MODEL_PRICES_JSON: "{" })["gpt-5.6-terra"].inputMilliPer1kToken, 20);
  assert.equal(billingInternals.configuredModelPrices({
    BILLING_MODEL_PRICES_JSON: JSON.stringify({ "my-model": {
      inputMilliPer1kToken: 4,
      outputMilliPer1kToken: 8,
      cachedMilliPer1kToken: 1,
      cachedWriteMilliPer1kToken: 2,
    } }),
  })["my-model"].cachedWriteMilliPer1kToken, 2);
});
