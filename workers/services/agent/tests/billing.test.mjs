import assert from "node:assert/strict";
import test from "node:test";
import { CoreAgentBillingMapper } from "../.test-build/mappers/core-agent-billing-mapper.js";

test("Core Agent billing mapper sends authenticated contracts and preserves idempotency keys", async () => {
  const requests = [];
  const mapper = new CoreAgentBillingMapper({
    CORE_SERVICE_URL: "https://core.example",
    CORE_SERVICE_TOKEN: "service-secret",
    CORE_SERVICE: {
      async fetch(request) {
        requests.push(request);
        return Response.json({ code: 200, message: "success", data: null });
      },
    },
  });

  assert.equal(await mapper.ensureAvailable(42), true);
  await mapper.chargeTokens({
    userId: 42,
    bizType: "agent-turn",
    bizKey: "conv:7:run:1",
    bizRef: "conv:7",
    inputTokens: 12,
    outputTokens: 5,
    cachedTokens: 3,
    model: "test-model",
    remark: "智能体会话单轮执行",
  });

  assert.equal(requests.length, 2);
  assert.equal(requests[0].url, "https://core.example/internal/agent/billing/ensure-available");
  assert.equal(requests[0].headers.get("x-dinqor-service-token"), "service-secret");
  assert.deepEqual(await requests[0].json(), { userId: 42 });
  assert.equal(requests[1].url, "https://core.example/internal/agent/billing/charge-tokens");
  assert.deepEqual(await requests[1].json(), {
    userId: 42,
    bizType: "agent-turn",
    bizKey: "conv:7:run:1",
    bizRef: "conv:7",
    inputTokens: 12,
    outputTokens: 5,
    cachedTokens: 3,
    model: "test-model",
    remark: "智能体会话单轮执行",
  });
});

test("Core Agent billing mapper treats HTTP 402 as an insufficient balance", async () => {
  const mapper = new CoreAgentBillingMapper({
    CORE_SERVICE_URL: "https://core.example",
    CORE_SERVICE_TOKEN: "service-secret",
    CORE_SERVICE: { async fetch() { return Response.json({ code: 402 }, { status: 402 }); } },
  });
  assert.equal(await mapper.ensureAvailable(42), false);
});

test("Core Agent billing mapper rejects non-HTTPS cross-account service URLs", async () => {
  const mapper = new CoreAgentBillingMapper({
    CORE_SERVICE_URL: "http://core.example",
    CORE_SERVICE_TOKEN: "service-secret",
  });
  await assert.rejects(mapper.ensureAvailable(42), /must use HTTPS/);
});
