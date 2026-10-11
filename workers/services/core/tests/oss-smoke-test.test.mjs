import assert from "node:assert/strict";
import test, { afterEach, mock } from "node:test";
import coreWorker from "../.test-build/index.js";

afterEach(() => mock.restoreAll());

function environment(overrides = {}) {
  return {
    CORE_OSS_SMOKE_TEST_ENABLED: "true",
    ALIYUN_OSS_ENDPOINT: "oss-cn-beijing.aliyuncs.com",
    ALIYUN_OSS_BUCKET: "dinqor",
    ALIYUN_OSS_REGION: "cn-beijing",
    ALIYUN_OSS_ACCESS_KEY_ID: "test-access-key",
    ALIYUN_OSS_ACCESS_KEY_SECRET: "test-access-secret",
    ...overrides,
  };
}

test("OSS smoke route is off by default and does not reach OSS", async () => {
  const upstream = mock.method(globalThis, "fetch", async () => {
    throw new Error("OSS should not be called");
  });
  const response = await coreWorker.fetch(
    new Request("https://core.test/__ops/oss-smoke-test", { method: "POST" }),
    environment({ CORE_OSS_SMOKE_TEST_ENABLED: "false" }),
  );
  assert.equal(response.status, 404);
  assert.equal(upstream.mock.callCount(), 0);
});

test("unauthenticated OSS smoke route uploads only its fixed tiny payload", async () => {
  const upstream = mock.method(globalThis, "fetch", async (input, init) => {
    assert.equal(String(input), "https://dinqor.oss-cn-beijing.aliyuncs.com/agent-attachments/diagnostics/core-oss-smoke-test.txt");
    assert.match(init.headers.get("authorization"), /^OSS4-HMAC-SHA256 Credential=test-access-key\//);
    if (init.method === "PUT") {
      assert.equal(init.headers.get("content-type"), "text/plain; charset=utf-8");
      assert.equal(new TextDecoder().decode(init.body), "DinQorAI Core OSS smoke test\n");
      return new Response(null, { status: 200, headers: { "x-oss-request-id": "put-request" } });
    }
    assert.equal(init.method, "HEAD");
    return new Response(null, { status: 200, headers: { "x-oss-request-id": "head-request" } });
  });
  const response = await coreWorker.fetch(
    new Request("https://core.test/__ops/oss-smoke-test", { method: "POST" }),
    environment({ INTERNAL_SERVICE_TOKEN: "service-token" }),
  );
  assert.equal(response.status, 200);
  assert.deepEqual((await response.json()).data, {
    uploaded: true,
    headVerified: true,
    objectKey: "agent-attachments/diagnostics/core-oss-smoke-test.txt",
    putStatus: 200,
    putRequestId: "put-request",
    headStatus: 200,
    headRequestId: "head-request",
  });
  assert.equal(upstream.mock.callCount(), 2);
});

test("OSS smoke route accepts an explicitly empty request body", async () => {
  const upstream = mock.method(globalThis, "fetch", async () => new Response(null, { status: 200 }));
  const response = await coreWorker.fetch(
    new Request("https://core.test/__ops/oss-smoke-test", { method: "POST", body: "" }),
    environment(),
  );
  assert.equal(response.status, 200);
  assert.equal(upstream.mock.callCount(), 2);
});

test("OSS smoke route rejects caller-supplied request bodies", async () => {
  const upstream = mock.method(globalThis, "fetch", async () => new Response(null, { status: 200 }));
  const response = await coreWorker.fetch(
    new Request("https://core.test/__ops/oss-smoke-test", {
      method: "POST",
      body: "caller content",
    }),
    environment(),
  );
  assert.equal(response.status, 400);
  assert.equal(upstream.mock.callCount(), 0);
});

test("OSS smoke route returns OSS status and safe error code on failed writes", async () => {
  mock.method(globalThis, "fetch", async () => new Response(
    "<Error><Code>InvalidAccessKeyId</Code></Error>",
    { status: 403, headers: { "x-oss-request-id": "request-403" } },
  ));
  const response = await coreWorker.fetch(
    new Request("https://core.test/__ops/oss-smoke-test", { method: "POST" }),
    environment(),
  );
  assert.equal(response.status, 502);
  assert.deepEqual((await response.json()).data, {
    ossStatus: 403,
    ossCode: "InvalidAccessKeyId",
    requestId: "request-403",
  });
});

test("OSS smoke route returns OSS status and safe error code when HEAD is rejected", async () => {
  let calls = 0;
  mock.method(globalThis, "fetch", async () => {
    calls++;
    if (calls === 1) return new Response(null, { status: 200 });
    return new Response(null, {
      status: 403,
      headers: { "x-oss-ec": "SignatureDoesNotMatch", "x-oss-request-id": "head-request-403" },
    });
  });
  const response = await coreWorker.fetch(
    new Request("https://core.test/__ops/oss-smoke-test", { method: "POST" }),
    environment(),
  );
  assert.equal(response.status, 502);
  assert.deepEqual((await response.json()).data, {
    operation: "head",
    ossStatus: 403,
    ossCode: "SignatureDoesNotMatch",
    requestId: "head-request-403",
  });
  assert.equal(calls, 2);
});
