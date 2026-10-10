import assert from "node:assert/strict";
import test, { afterEach, mock } from "node:test";
import { CoreBlogPlanTopicMapper } from "../.test-build/mappers/core-blog-plan-topic-mapper.js";

afterEach(() => mock.restoreAll());

test("Content topic mapper uses the protected Core HTTPS contract", async () => {
  let call;
  mock.method(globalThis, "fetch", async (url, init) => {
    call = { url: String(url), headers: new Headers(init.headers), body: JSON.parse(init.body) };
    return Response.json({ topics: ["D1 schema evolution"] });
  });
  const mapper = new CoreBlogPlanTopicMapper({
    CORE_SERVICE_URL: "https://core.example/", CORE_SERVICE_TOKEN: "local-test-token",
  });
  const result = await mapper.generateTopics(42, {
    seed: "Cloudflare D1", count: 1, excludeTitles: ["Used title"],
  });
  assert.deepEqual(result, ["D1 schema evolution"]);
  assert.equal(call.url, "https://core.example/internal/agent/blog/topic-series");
  assert.equal(call.headers.get("x-dinqor-service-token"), "local-test-token");
  assert.deepEqual(call.body, {
    userId: 42, seed: "Cloudflare D1", count: 1, excludeTitles: ["Used title"],
  });
});
