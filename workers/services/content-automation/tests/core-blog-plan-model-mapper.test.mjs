import assert from "node:assert/strict";
import test, { afterEach, mock } from "node:test";
import { CoreBlogPlanModelMapper } from "../.test-build/mappers/core-blog-plan-model-mapper.js";

afterEach(() => mock.restoreAll());

test("plan model mapper calls Core stage and billing contracts with service authentication", async () => {
  const requests = [];
  mock.method(globalThis, "fetch", async (url, init) => {
    requests.push({ url: String(url), headers: new Headers(init.headers), body: JSON.parse(init.body) });
    return requests.length === 2
      ? Response.json({ payloadJson: "{\"angle\":\"D1 safety\"}", inputTokens: 2, cachedTokens: 1, outputTokens: 3, model: "blog-model" })
      : Response.json({ code: 200, message: "success", data: null });
  });
  const mapper = new CoreBlogPlanModelMapper({ CORE_SERVICE_URL: "https://core.example/", CORE_SERVICE_TOKEN: "test-token" });
  await mapper.ensureAvailable(42);
  const result = await mapper.runStage("analyze", { input: "Cloudflare D1" });
  await mapper.chargeTokens({
    userId: 42, planId: 9, runId: 77,
    usage: { inputTokens: 2, cachedTokens: 1, outputTokens: 3 }, model: "blog-model",
  });
  assert.deepEqual(JSON.parse(result.payloadJson), { angle: "D1 safety" });
  assert.deepEqual(requests.map((request) => request.url), [
    "https://core.example/internal/agent/billing/ensure-available",
    "https://core.example/internal/agent/blog/model/analyze",
    "https://core.example/internal/agent/billing/charge-tokens",
  ]);
  assert.ok(requests.every((request) => request.headers.get("x-dinqor-service-token") === "test-token"));
  assert.deepEqual(requests[2].body, {
    userId: 42, bizType: "BLOG_AGENT_RUN", bizKey: "plan-run:77", bizRef: "plan:9",
    inputTokens: 2, cachedTokens: 1, outputTokens: 3, model: "blog-model", remark: "定时博客生成",
  });
});

test("plan model mapper surfaces Core insufficient-point responses", async () => {
  mock.method(globalThis, "fetch", async () => Response.json({ code: 402, message: "积分不足" }, { status: 402 }));
  const mapper = new CoreBlogPlanModelMapper({ CORE_SERVICE_URL: "https://core.example", CORE_SERVICE_TOKEN: "test-token" });
  await assert.rejects(mapper.ensureAvailable(42), /积分不足/);
});
