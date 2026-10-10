import assert from "node:assert/strict";
import test, { afterEach, mock } from "node:test";
import integrationWorker from "../.test-build/index.js";

const serviceToken = "platform-integration-test-token";

function request(body, { token = serviceToken } = {}) {
  return new Request("https://integration.test/internal/mail/verification-code", {
    method: "POST",
    headers: {
      ...(token ? { "x-dinqor-service-token": token } : {}),
      "content-type": "application/json",
    },
    body: JSON.stringify(body),
  });
}

function environment(overrides = {}) {
  return {
    INTERNAL_SERVICE_TOKEN: serviceToken,
    RESEND_API_KEY: "test-resend-key",
    MAIL_FROM: "noreply@example.com",
    ...overrides,
  };
}

afterEach(() => mock.restoreAll());

test("verification mail validates its caller and request before delivery", async () => {
  const unauthenticated = await integrationWorker.fetch(
    request({ email: "alice@example.com", code: "123456" }, { token: null }),
    environment(),
  );
  assert.equal(unauthenticated.status, 401);

  const malformed = await integrationWorker.fetch(
    request({ email: "not-an-email", code: "12345" }),
    environment(),
  );
  assert.equal(malformed.status, 400);
});

test("verification mail sends through Resend without logging delivery contents", async () => {
  let deliveredRequest;
  mock.method(globalThis, "fetch", async (input, init) => {
    deliveredRequest = { url: String(input), init };
    return Response.json({ id: "email_test_123" }, { status: 200 });
  });

  const response = await integrationWorker.fetch(
    request({ email: "alice@example.com", code: "123456" }),
    environment(),
  );
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { sent: true });
  assert.equal(deliveredRequest.url, "https://api.resend.com/emails");
  assert.equal(deliveredRequest.init.headers.authorization, "Bearer test-resend-key");
  const mail = JSON.parse(deliveredRequest.init.body);
  assert.deepEqual(mail.to, ["alice@example.com"]);
  assert.equal(mail.from, "noreply@example.com");
  assert.match(mail.text, /123456/);
});

test("verification mail reports missing configuration and Resend failures safely", async () => {
  const missingConfig = await integrationWorker.fetch(
    request({ email: "alice@example.com", code: "123456" }),
    environment({ RESEND_API_KEY: undefined }),
  );
  assert.equal(missingConfig.status, 503);

  mock.method(globalThis, "fetch", async () => new Response("provider error", { status: 500 }));
  const failedDelivery = await integrationWorker.fetch(
    request({ email: "alice@example.com", code: "123456" }),
    environment(),
  );
  assert.equal(failedDelivery.status, 503);
  assert.doesNotMatch(await failedDelivery.text(), /provider error|123456|test-resend-key/);
});
