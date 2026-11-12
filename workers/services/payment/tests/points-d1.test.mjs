import assert from "node:assert/strict";
import test from "node:test";
import { DatabaseSync } from "node:sqlite";
import { readdirSync, readFileSync } from "node:fs";
import { D1PaymentOrderMapper } from "../.test-build/mappers/d1-payment-order-mapper.js";
import { D1PaymentSettingsMapper } from "../.test-build/mappers/d1-payment-settings-mapper.js";

test("payment D1 persists quantity, points snapshot and optional points SKU across config updates", async () => {
  const sql = new DatabaseSync(":memory:");
  const dir = new URL("../migrations/", import.meta.url);
  for (const file of readdirSync(dir)
    .filter((f) => f.endsWith(".sql"))
    .sort())
    sql.exec(readFileSync(new URL(file, dir), "utf8"));
  const db = {
    prepare(query) {
      let args = [];
      const stmt = {
        bind(...values) {
          args = values;
          return stmt;
        },
        async first() {
          return sql.prepare(query).get(...args) ?? null;
        },
        async run() {
          const r = sql.prepare(query).run(...args);
          return { success: true, meta: { changes: Number(r.changes) } };
        },
      };
      return stmt;
    },
  };
  const settings = new D1PaymentSettingsMapper(db);
  const input = {
    enabled: true,
    offerId: "test",
    encryptedAppKey: "encrypted",
    pointsProductId: "ten-points",
    plus: { productId: "PLUS", priceCents: 200, durationDays: 30 },
    pro: { productId: "PRO", priceCents: 400, durationDays: 30 },
    ultra: { productId: "ULTRA", priceCents: 1000, durationDays: 30 },
  };
  await settings.saveSettings(input);
  assert.equal((await settings.findSettings()).pointsProductId, "ten-points");
  const { pointsProductId, ...legacyInput } = input;
  await settings.saveSettings(legacyInput);
  assert.equal((await settings.findSettings()).pointsProductId, "ten-points");
  const cards = [10, 50, 100].map((points) => ({
    productId: `POINTS_${points}`,
    name: `${points}积分卡`,
    priceCents: points * 10,
    points,
    enabled: true,
  }));
  await settings.saveSettings({ ...input, pointsProducts: cards });
  assert.deepEqual(JSON.parse((await settings.findSettings()).pointsProductsJson), cards);
  await settings.saveSettings({ ...legacyInput, pointsProductId: "old-admin" });
  assert.deepEqual(JSON.parse((await settings.findSettings()).pointsProductsJson), cards);
  await settings.saveSettings({ ...legacyInput, pointsProducts: [] });
  assert.deepEqual(JSON.parse((await settings.findSettings()).pointsProductsJson), []);
  const orders = new D1PaymentOrderMapper(db);
  await orders.insertPending({
    outTradeNo: "Dtest",
    userId: 1,
    openid: "test",
    productId: pointsProductId,
    tier: "PLUS",
    goodsPrice: 100,
    quantity: 3,
    durationDays: 30,
    kind: "points",
    points: 30,
  });
  const order = await orders.findOwnedOrder(1, "Dtest");
  assert.equal(order.quantity, 3);
  assert.equal(order.points, 30);
  assert.equal(order.kind, "points");
  assert.equal(await orders.findOwnedOrder(2, "Dtest"), null);
  const inputOrder = { ...order, outTradeNo: 'second' };
  const inserts = await Promise.all([orders.insertPending(inputOrder), orders.insertPending({...inputOrder,outTradeNo:'third'})]);
  assert.deepEqual(inserts, [false,false]);
  assert.equal((await orders.findPayable(1, pointsProductId, 3)).outTradeNo, 'Dtest');
  assert.equal(await orders.findPayable(2, pointsProductId, 3), null);
  sql.exec("UPDATE virtual_pay_order SET created_at=strftime('%Y-%m-%dT%H:%M:%fZ','now','-15 minutes')");
  assert.equal(await orders.findPayable(1, pointsProductId, 3), null);
  assert.equal(await orders.insertPending(inputOrder), true);
  assert.equal((await orders.findPayable(1, pointsProductId, 3)).outTradeNo, 'second');

});
