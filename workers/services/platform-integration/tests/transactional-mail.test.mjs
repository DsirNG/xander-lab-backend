import assert from "node:assert/strict";
import test from "node:test";
import { handleTransactionalMailRequest } from "../.test-build/controllers/transactional-mail-controller.js";
import { ResendTransactionalMailMapper } from "../.test-build/mappers/resend-transactional-mail-mapper.js";
import { TransactionalMailService } from "../.test-build/services/transactional-mail-service.js";

test("reminder mail contract requires the Platform service token and validates payloads", async () => {
  const delivered = [];
  const service = new TransactionalMailService({
    isConfigured: () => true,
    async send(input) { delivered.push(input); return true; },
  });
  const env = { INTERNAL_SERVICE_TOKEN: "platform-secret" };
  const makeRequest = (token, body) => new Request("https://platform.test/internal/platform-integration/mail/reminder", {
    method: "POST",
    headers: { ...(token ? { "x-dinqor-service-token": token } : {}), "content-type": "application/json" },
    body: JSON.stringify(body),
  });
  const denied = await handleTransactionalMailRequest(makeRequest(null, {}), env, service);
  assert.equal(denied.status, 404);
  const invalid = await handleTransactionalMailRequest(makeRequest("platform-secret", { email: "bad", subject: "hi", text: "hi" }), env, service);
  assert.equal(invalid.status, 400);
  const sent = await handleTransactionalMailRequest(makeRequest("platform-secret", {
    email: "person@example.test", subject: "Reminder", text: "Hello", html: "<p>Hello</p>",
  }), env, service);
  assert.equal(sent.status, 200);
  assert.deepEqual(delivered[0], { email: "person@example.test", subject: "Reminder", text: "Hello", html: "<p>Hello</p>" });
});

test("Resend reminder mapper sends only the intended payload and handles missing configuration", async () => {
  const previousFetch = globalThis.fetch;
  const calls = [];
  globalThis.fetch = async (url, init) => {
    calls.push({ url: String(url), init });
    return new Response(null, { status: 202 });
  };
  try {
    const missing = new ResendTransactionalMailMapper({});
    assert.equal(await missing.send({ email: "x@example.test", subject: "s", text: "t", html: null }), false);
    const mapper = new ResendTransactionalMailMapper({ RESEND_API_KEY: "test-key", MAIL_FROM: "noreply@example.test" });
    assert.equal(await mapper.send({ email: "x@example.test", subject: "s", text: "t", html: "<b>t</b>" }), true);
    assert.equal(calls[0].url, "https://api.resend.com/emails");
    assert.equal(calls[0].init.headers.authorization, "Bearer test-key");
    assert.deepEqual(JSON.parse(calls[0].init.body), {
      from: "noreply@example.test", to: ["x@example.test"], subject: "s", text: "t", html: "<b>t</b>",
    });
  } finally {
    globalThis.fetch = previousFetch;
  }
});
