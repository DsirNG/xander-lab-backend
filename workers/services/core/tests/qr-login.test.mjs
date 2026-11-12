import assert from "node:assert/strict";
import test from "node:test";
import { QrLoginService } from "../.test-build/services/qr-login-service.js";
import { handleQrLoginRequest } from "../.test-build/controllers/qr-login-controller.js";

function fixture() {
  const states = new Map();
  const repository = {
    async save(ticket, state) { states.set(ticket, state); },
    async find(ticket) { return states.get(ticket) ?? null; },
    async update(ticket, statuses, updates) {
      const state = states.get(ticket);
      if (!state || !statuses.includes(state.status)) return null;
      Object.assign(state, updates);
      return state;
    },
    async consume(ticket, status, code) {
      const state = states.get(ticket);
      if (!state || state.status !== status || state.exchangeCode !== code) return null;
      states.delete(ticket);
      return state;
    },
    async delete(ticket) { states.delete(ticket); },
  };
  return { service: new QrLoginService(repository), states };
}

const now = 1_800_000_000_000;
test("creation stores bounded device identifiers and millisecond timestamps", async () => {
  const { service, states } = fixture();
  for (const [ua, os, browser] of [
    ["Mozilla/5.0 (Windows NT 10.0) Chrome/130.0 Safari/537.36 Edg/130.0", "windows", "edge"],
    ["Mozilla/5.0 (Macintosh; Intel Mac OS X) Version/18 Safari/605.1", "macos", "safari"],
    ["Linux Firefox/130.0", "linux", "firefox"],
    ["Windows Chrome/130.0 Safari/537.36 OPR/115.0", "windows", "opera"],
    ["Android Chrome/130.0", "android", "chrome"],
    ["iPhone CriOS/130.0 Safari/605.1", "ios", "chrome"],
    ["CrOS Chrome/130.0", "chromeos", "chrome"],
    ["private arbitrary user agent", "unknown", "unknown"],
    ["", "unknown", "unknown"],
  ]) {
    const { ticket, expiresAt } = await service.create(now, ua);
    assert.equal(expiresAt, now + 120_000);
    assert.deepEqual(await service.status(ticket, now), {
      status: "WAITING", exchangeCode: null, device: { os, browser },
      createdAt: now, expiresAt,
    });
    if (ua) assert.equal(JSON.stringify(states.get(ticket)).includes(ua), false);
  }
});

test("stale records expire at the exact deadline in status, confirm, and exchange", async () => {
  const { service, states } = fixture();
  const { ticket, expiresAt } = await service.create(now);
  assert.equal(await service.confirm(ticket, 7, now + 1), true);
  const code = (await service.status(ticket, now + 2)).exchangeCode;
  assert.ok(code);
  assert.equal(await service.confirm(ticket, 8, expiresAt), false);
  const expired = await service.status(ticket, expiresAt);
  assert.equal(expired.status, "EXPIRED");
  assert.equal(expired.exchangeCode, null);
  assert.equal(expired.createdAt, now);
  assert.equal(expired.expiresAt, expiresAt);
  assert.equal("userId" in expired, false);
  assert.ok(states.has(ticket)); // Fake Redis deliberately ignores TTL.
  assert.equal(await service.exchange(ticket, code, expiresAt), null);
  for (const invalid of [undefined, NaN, Infinity, "future"]) {
    states.set(ticket, { status: "CONFIRMED", expiresAt: invalid, exchangeCode: code, userId: "7" });
    assert.equal((await service.status(ticket, now)).status, "EXPIRED");
    assert.equal(await service.confirm(ticket, 8, now), false);
    assert.equal(await service.exchange(ticket, code, now), null);
  }
});

test("valid exchange remains single use and wrong codes do not consume it", async () => {
  const { service } = fixture();
  const { ticket } = await service.create(now);
  assert.equal(await service.confirm(ticket, 7, now + 1), true);
  assert.equal(await service.confirm(ticket, 8, now + 1), false);
  const { exchangeCode } = await service.status(ticket, now + 2);
  assert.equal(await service.exchange(ticket, "wrong", now + 3), null);
  assert.deepEqual(await Promise.all([
    service.exchange(ticket, exchangeCode, now + 3),
    service.exchange(ticket, exchangeCode, now + 3),
  ]), [7, null]);
  assert.deepEqual(await service.status(ticket, now + 4), { status: "EXPIRED", exchangeCode: null });
});

test("HTTP create captures the requesting desktop, status exposes only safe metadata", async () => {
  const { service } = fixture();
  const dependencies = {
    service, enforceRateLimit: async () => null,
    qrImages: { createLoginQr: async () => "data:image/png;base64,test" },
  };
  const response = await handleQrLoginRequest(new Request("https://example.test/api/auth/qr/create", {
    method: "POST", headers: { "User-Agent": "Windows Chrome/130.0 Safari/537.36" },
  }), dependencies);
  const { data: created } = await response.json();
  const status = await handleQrLoginRequest(new Request(
    `https://example.test/api/auth/qr/status?ticket=${created.ticket}`,
    { headers: { "User-Agent": "iPhone Safari/605.1" } },
  ), dependencies);
  const { data } = await status.json();
  assert.deepEqual(data.device, { os: "windows", browser: "chrome" });
  assert.equal(typeof data.createdAt, "number");
  assert.equal(data.expiresAt - data.createdAt, 120_000);
  assert.deepEqual(Object.keys(data).sort(), ["createdAt", "device", "exchangeCode", "expiresAt", "status"]);
});

