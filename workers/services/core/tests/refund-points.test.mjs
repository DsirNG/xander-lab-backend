import assert from "node:assert/strict";
import test from "node:test";
import { DatabaseSync } from "node:sqlite";
import { readdirSync, readFileSync } from "node:fs";
import { D1PaymentRefundMapper } from "../.test-build/mappers/d1-payment-refund-mapper.js";
import { D1BillingMapper } from "../.test-build/mappers/d1-billing-mapper.js";
import { D1PaymentEntitlementMapper } from "../.test-build/mappers/d1-payment-entitlement-mapper.js";

function fixture() {
  const sql = new DatabaseSync(":memory:");
  const dir = new URL("../migrations/", import.meta.url);
  for (const file of readdirSync(dir)
    .filter((f) => f.endsWith(".sql"))
    .sort())
    sql.exec(readFileSync(new URL(file, dir), "utf8"));
  sql.exec(
    "INSERT INTO sys_user(id,username) VALUES(1,'one'),(2,'two'); INSERT INTO sys_user_points(user_id,balance) VALUES(1,10000),(2,5000)",
  );
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
        async all() {
          return { results: sql.prepare(query).all(...args) };
        },
        async run() {
          const row = sql.prepare(query).run(...args);
          return { success: true, meta: { changes: Number(row.changes) } };
        },
      };
      return stmt;
    },
    async batch(stmts) {
      sql.exec("BEGIN");
      try {
        const rows = [];
        for (const s of stmts) rows.push(await s.run());
        sql.exec("COMMIT");
        return rows;
      } catch (e) {
        sql.exec("ROLLBACK");
        throw e;
      }
    },
  };
  return {
    sql,
    refunds: new D1PaymentRefundMapper(db),
    billing: new D1BillingMapper(db),
    entitlements: new D1PaymentEntitlementMapper(db),
  };
}
const debit = (billing, key, amount) =>
  billing.appendLedger({
    userId: 1,
    bizType: "chat",
    bizKey: key,
    bizRef: null,
    amount,
    balanceDelta: -amount,
    unitPrice: "test",
    model: null,
    inputTokens: null,
    outputTokens: null,
    cachedTokens: null,
    remark: null,
  });

test("purchase lots debit FIFO after subscription and reserve only the original order", async () => {
  const { sql, billing, entitlements, refunds } = fixture();
  await entitlements.grantOnce(1, "PLUS", 30, "virtualpay:plan");
  await entitlements.grantPoints(1, 100000, "virtualpay:pointsA");
  await entitlements.grantPoints(1, 50000, "virtualpay:pointsB");
  await debit(billing, "consume", 50000);
  assert.equal((await refunds.quote(1, "virtualpay:pointsA")).remaining, 70000);
  assert.equal((await refunds.quote(1, "virtualpay:pointsB")).remaining, 50000);
  assert.equal(await refunds.quote(2, "virtualpay:pointsA"), undefined ?? null);
  const before = (await billing.wallet(1)).balance;
  const hold = await refunds.reserve(1, "virtualpay:pointsA", "RefundOne");
  assert.equal(hold.amount, 70000);
  assert.equal((await billing.wallet(1)).balance, before - 70000);
  await refunds.reserve(1, "virtualpay:pointsA", "RefundOne");
  assert.equal((await billing.wallet(1)).balance, before - 70000);
  assert.equal(
    await refunds.reserve(1, "virtualpay:pointsA", "RefundOther"),
    null,
  );
  await debit(billing, "consume-more", 10000);
  assert.equal((await refunds.quote(1, "virtualpay:pointsB")).remaining, 40000);
  assert.equal(
    await refunds.settle(1, "virtualpay:pointsA", "RefundOne", false),
    true,
  );
  assert.equal(
    await refunds.settle(1, "virtualpay:pointsA", "RefundOne", false),
    true,
  );
  assert.equal((await refunds.quote(1, "virtualpay:pointsA")).remaining, 70000);
  assert.equal((await billing.wallet(1)).balance, before - 10000);
  assert.equal(await billing.findConsumedToday(1), 60000);
  assert.equal(
    await refunds.settle(1, "virtualpay:pointsA", "RefundOne", true),
    false,
  );
  sql.close();
});
test("subscription refund revokes its membership, never a subsequent upgrade", async () => {
  const { sql, billing, entitlements, refunds } = fixture();
  await entitlements.grantOnce(1, "PLUS", 30, "virtualpay:old");
  await debit(billing, "use", 5000);
  const hold = await refunds.reserve(1, "virtualpay:old", "RefundOld");
  assert.equal(hold.amount, 15000);
  await entitlements.grantOnce(1, "PRO", 30, "virtualpay:new");
  assert.equal(
    await refunds.settle(1, "virtualpay:old", "RefundOld", true),
    true,
  );
  assert.equal(
    sql.prepare("SELECT tier FROM sys_user WHERE id=1").get().tier,
    "PRO",
  );
  await refunds.reserve(1, "virtualpay:new", "RefundNew");
  assert.equal(
    await refunds.settle(1, "virtualpay:new", "RefundNew", true),
    true,
  );
  assert.equal(
    sql.prepare("SELECT tier FROM sys_user WHERE id=1").get().tier,
    "FREE",
  );
  sql.close();
});
test("failed refund after expiry does not resurrect expired points", async () => {
  const { sql, billing, entitlements, refunds } = fixture();
  await entitlements.grantOnce(1, "PLUS", 30, "virtualpay:plan");
  await refunds.reserve(1, "virtualpay:plan", "RefundExpiry");
  sql.exec(
    "UPDATE subscription_points_lot SET expires_at='2000-01-01T00:00:00Z'",
  );
  await refunds.settle(1, "virtualpay:plan", "RefundExpiry", false);
  assert.equal((await billing.wallet(1)).balance, 10000);
  await entitlements.grantPoints(1, 10000, "virtualpay:new");
  assert.equal((await billing.wallet(1)).balance, 20000);
  sql.close();
});
test("historical untracked balances are not attributed to a new purchase; merge during hold rolls back", async () => {
  const { sql, billing, entitlements, refunds } = fixture();
  assert.equal(await refunds.quote(1, "virtualpay:historical"), null);
  await entitlements.grantPoints(1, 10000, "virtualpay:new");
  await refunds.reserve(1, "virtualpay:new", "RefundMerge");
  await assert.rejects(
    billing.transferBalance(1, 2, "merge-hold", "test", "merge"),
    /refund_in_progress/,
  );
  assert.equal((await billing.wallet(2)).balance, 5000);
  assert.equal((await refunds.quote(1, "virtualpay:new")).remaining, 0);
  await refunds.settle(1, "virtualpay:new", "RefundMerge", false);
  await billing.transferBalance(1, 2, "merge-ok", "test", "merge");
  assert.equal((await refunds.quote(2, "virtualpay:new")).remaining, 10000);
  sql.close();
});
