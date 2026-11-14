import assert from "node:assert/strict";
import test from "node:test";
import { PaymentHandoffService } from "../.test-build/services/payment-handoff-service.js";
import { handlePaymentHandoffRequest } from "../.test-build/controllers/payment-handoff-controller.js";

function fixture() {
  const states = new Map();
  const repository = {
    async save(ticket, state) { states.set(ticket, { ...state }); },
    async find(ticket) { return states.get(ticket) ?? null; },
    async attach(ticket, outTradeNo) {
      const state = states.get(ticket);
      if (state?.status === "WAITING") states.set(ticket, { ...state, outTradeNo, status: "ORDER_CREATED" });
    },
    async delete(ticket) { states.delete(ticket); },
  };
  const service = new PaymentHandoffService(repository);
  const deps = {
    service, createQr: async () => "data:image/png;base64,test",
    resolveUser: async () => ({ id: 7 }), rateLimit: async () => null,
  };
  return { service, deps, states };
}
const now = 1_800_000_000_000;
test("handoffs require the creator account and expire at the exact deadline", async () => {
  const { service } = fixture();
  const intent = await service.create(7, "pro-month", 1, null, now);
  assert.match(intent.ticket, /^[a-f0-9]{32}$/);
  assert.equal(intent.expiresAt, now + 300000);
  assert.equal(await service.read(intent.ticket, 8, now), null);
  assert.equal(await service.attach(intent.ticket, 8, "other-order", now), null);
  assert.equal(await service.read(intent.ticket, 7, intent.expiresAt), null);
  assert.equal(await service.attach(intent.ticket, 7, "order1", intent.expiresAt), null);
  assert.equal((await service.read(intent.ticket, 7, now)).outTradeNo, null);
});
test("only the first attached order is kept, including continuation orders", async () => {
  const { service } = fixture();
  const intent = await service.create(7, "pro-month", 1, null, now);
  await Promise.all([service.attach(intent.ticket, 7, "first", now), service.attach(intent.ticket, 7, "second", now)]);
  const state = await service.read(intent.ticket, 7, now);
  assert.equal(state.outTradeNo, "first");
  assert.equal(state.expiresAt, intent.expiresAt);
  assert.equal("paid" in state, false);
  const resume = await service.create(7, "pro-month", 1, "existing", now);
  assert.equal((await service.attach(resume.ticket, 7, "replacement", now)).outTradeNo, "existing");
});
test("controller authenticates all reads and writes, bounds input and removes failed QR intents", async () => {
  const { deps, states } = fixture();
  const post = (body) => new Request("https://core.test/api/payment-handoffs", { method: "POST", body: JSON.stringify(body) });
  for (const body of [null, [], { productId: "bad/path" }, { productId: "pro", quantity: 1.5 }, { productId: "pro", quantity: 1001 }]) {
    assert.equal((await handlePaymentHandoffRequest(post(body), deps)).status, 400);
  }
  const failed = await handlePaymentHandoffRequest(post({ productId: "pro" }), { ...deps, createQr: async () => null });
  assert.equal(failed.status, 503);
  assert.equal(states.size, 0);
  const response = await handlePaymentHandoffRequest(post({ productId: "pro" }), deps);
  assert.equal(response.headers.get("cache-control"), "no-store");
  const { data } = await response.json();
  const read = new Request(`https://core.test/api/payment-handoffs/${data.ticket}`);
  assert.equal((await handlePaymentHandoffRequest(read, { ...deps, resolveUser: async () => ({ id: 8 }) })).status, 404);
  const unauthorized = { ...deps, resolveUser: async () => new Response(null, { status: 401 }) };
  assert.equal((await handlePaymentHandoffRequest(read, unauthorized)).status, 401);
  assert.equal((await handlePaymentHandoffRequest(post({ productId: "pro" }), unauthorized)).status, 401);
  const limited = await handlePaymentHandoffRequest(post({ productId: "pro" }), { ...deps, rateLimit: async () => new Response(null, { status: 429 }) });
  assert.equal(limited.status, 429);
  assert.equal(states.size, 1);
});
