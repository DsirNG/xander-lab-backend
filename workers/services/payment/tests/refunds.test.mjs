import assert from "node:assert/strict";
import test from "node:test";
import { DatabaseSync } from "node:sqlite";
import { readdirSync, readFileSync } from "node:fs";
import {
  PaymentRefundService,
  proportionalRefund,
  refundWindowOpen,
} from "../.test-build/services/payment-refund-service.js";
import { D1PaymentRefundMapper } from "../.test-build/mappers/d1-payment-refund-mapper.js";
import { D1PaymentOrderMapper } from "../.test-build/mappers/d1-payment-order-mapper.js";
import { handlePaymentRequest } from "../.test-build/controllers/payment-controller.js";
import { WechatMessageMapper } from "../.test-build/mappers/wechat-message-mapper.js";

async function fixture(options = {}) {
  const sql = new DatabaseSync(":memory:");
  const dir = new URL("../migrations/", import.meta.url);
  for (const file of readdirSync(dir)
    .filter((f) => f.endsWith(".sql"))
    .sort())
    sql.exec(readFileSync(new URL(file, dir), "utf8"));
  const db = {
    prepare(query) {
      let args = [];
      const s = {
        bind(...a) {
          args = a;
          return s;
        },
        async first() {
          return sql.prepare(query).get(...args) ?? null;
        },
        async all() {
          return { results: sql.prepare(query).all(...args) };
        },
        async run() {
          return {
            success: true,
            meta: { changes: Number(sql.prepare(query).run(...args).changes) },
          };
        },
      };
      return s;
    },
  };
  const orders = new D1PaymentOrderMapper(db);
  const refunds = new D1PaymentRefundMapper(db);
  await orders.insertPending({
    outTradeNo: "order1234",
    userId: 1,
    openid: "openid1",
    productId: "plan",
    tier: "PLUS",
    goodsPrice: 200,
    quantity: 1,
    durationDays: 30,
    kind: options.kind ?? "subscription",
    points: 20,
  });
  const row = await orders.findOrder("order1234");
  await orders.markDelivered(row.id, "wx-order");
  let remaining = 15000,
    hold = null,
    submissions = 0,
    completeCount = 0,
    releaseCount = 0,
    providerStatus = 1;
  const paid = {
    paidTime: Math.floor(Date.now() / 1000) - 60,
    paid: true,
    wxOrderId: "wx-order",
    paidFee: 200,
    leftFee: 200,
    orderType: options.apple ? 7 : 0,
    status: 4,
  };
  const core = {
    async refundPoints(action, _user, _event, refundId) {
      if (options.legacy) return null;
      if (action === "quote") return { original: 20000, remaining };
      if (action === "reserve") {
        if (!hold) {
          hold = {
            original: 20000,
            remaining: 0,
            amount: remaining,
            state: "HELD",
            refundId,
          };
          remaining = 0;
        }
        return { ...hold };
      }
      if (!hold) return false;
      const state = action === "complete" ? "COMPLETED" : "RELEASED";
      if (hold.state === "HELD") {
        hold.state = state;
        if (action === "complete") completeCount++;
        else {
          remaining += hold.amount;
          releaseCount++;
        }
      }
      return hold.state === state;
    },
  };
  const wechat = {
    async queryOrder(_id, order) {
      return order === "order1234"
        ? { ...paid }
        : {
            paid: false,
            wxOrderId: "wx-refund",
            paidFee: 150,
            orderType: options.apple ? 8 : 1,
            status: providerStatus,
          };
    },
    async refundOrder(input) {
      submissions++;
      assert.equal(input.refund_fee, 150);
      assert.equal(input.left_fee, 200);
      assert.match(input.refund_order_id, /^R[a-f0-9]{30}$/);
      if (options.timeout) throw new Error("timeout");
      return { accepted: true, code: 0, wxRefundId: "wx-refund" };
    },
  };
  const service = new PaymentRefundService(
    orders,
    refunds,
    core,
    wechat,
    {
      async resolve() {
        return { appKey: "test-key" };
      },
    },
    {},
  );
  return {
    sql,
    service,
    orders,
    refunds,
    paid,
    stats: () => ({
      remaining,
      hold,
      submissions,
      completeCount,
      releaseCount,
    }),
    setProviderStatus(value) {
      providerStatus = value;
    },
  };
}

test("integer proportional refunds round down, never over-refund", () => {
  assert.equal(proportionalRefund(1000, 700, 1000), 700);
  assert.equal(proportionalRefund(200, 15000, 20000), 150);
  assert.equal(proportionalRefund(100, 1, 3), 33);
  assert.throws(() => proportionalRefund(100, 4, 3));
});
test("orders are scoped, paginated, and never reveal payment identity", async () => {
  const f = await fixture();
  const page = await f.service.list(1);
  assert.equal(page.records.length, 1);
  assert.equal(page.records[0].amount, 200);
  assert.equal("openid" in page.records[0], false);
  assert.equal((await f.service.list(2)).records.length, 0);
  await assert.rejects(
    f.service.quote(2, "order1234"),
    (e) => e.status === 404,
  );
  await assert.rejects(
    f.service.request(2, "order1234", 150),
    (e) => e.status === 404,
  );
  f.sql.close();
});
test("subscription and points use the same unused-point policy; concurrent requests submit once", async () => {
  for (const kind of ["subscription", "points"]) {
    const f = await fixture({ kind });
    const quote = await f.service.quote(1, "order1234");
    assert.equal(quote.amount, 150);
    assert.equal(quote.terminatesSubscription, kind === "subscription");
    await Promise.all([
      f.service.request(1, "order1234", 150),
      f.service.request(1, "order1234", 150),
    ]);
    assert.equal(f.stats().submissions, 1);
    assert.equal(f.stats().remaining, 0);
    assert.equal((await f.refunds.find("order1234")).status, "PROCESSING");
    f.paid.leftFee = 50;
    f.setProviderStatus(8);
    await f.service.reconcile(1, "order1234");
    await f.service.reconcile(1, "order1234");
    assert.equal(f.stats().completeCount, 1);
    assert.equal((await f.orders.findOrder("order1234")).status, "REFUNDED");
    f.sql.close();
  }
});
test("timeouts retain the hold, do not resubmit, and state 5 is not presumed completed", async () => {
  const f = await fixture({ timeout: true });
  await f.service.request(1, "order1234", 150);
  assert.equal((await f.refunds.find("order1234")).status, "SUBMITTING");
  f.setProviderStatus(5);
  f.paid.leftFee = 50;
  await f.service.request(1, "order1234", 150);
  await f.service.sweep();
  assert.equal(f.stats().submissions, 1);
  assert.equal(f.stats().releaseCount, 0);
  assert.equal(f.stats().completeCount, 0);
  f.setProviderStatus(8);
  await f.service.sweep();
  assert.equal(f.stats().completeCount, 1);
  f.sql.close();
});
test("confirmed provider failure releases points once; changed quote cannot trigger a charge", async () => {
  const f = await fixture();
  await assert.rejects(
    f.service.request(1, "order1234", 200),
    (e) => e.status === 409,
  );
  assert.equal(await f.refunds.find("order1234"), null);
  await f.service.request(1, "order1234", 150);
  f.setProviderStatus(7);
  await f.service.reconcile(1, "order1234");
  await f.service.reconcile(1, "order1234");
  assert.equal(f.stats().remaining, 15000);
  assert.equal(f.stats().releaseCount, 1);
  assert.equal((await f.refunds.find("order1234")).status, "FAILED");
  f.sql.close();
});
test("Apple is routed to Apple; confirmed external full refund recovers points without sending a refund", async () => {
  const f = await fixture({ apple: true });
  assert.equal((await f.service.quote(1, "order1234")).reason, "APPLE");
  await assert.rejects(
    f.service.request(1, "order1234", 150),
    (e) => e.status === 409,
  );
  f.paid.leftFee = 0;
  f.setProviderStatus(8);
  const event = {
    tradeNo: "order1234",
    openid: "openid1",
    refundId: "AppleRefund123",
    wxOrderId: "wx-order",
    wxRefundId: "wx-refund",
    fee: 200,
    success: true,
  };
  await f.service.notification(event);
  await f.service.notification(event);
  assert.equal(f.stats().submissions, 0);
  assert.equal(f.stats().completeCount, 1);
  f.sql.close();
});
test("callback cannot complete a refund without confirmed movement, and wrong identities are rejected", async () => {
  const f = await fixture();
  await f.service.request(1, "order1234", 150);
  const refund = await f.refunds.find("order1234");
  const event = {
    tradeNo: "order1234",
    openid: "openid1",
    refundId: refund.refundId,
    wxOrderId: "wx-order",
    wxRefundId: "wx-refund",
    fee: 150,
    success: true,
  };
  await assert.rejects(f.service.notification({ ...event, openid: "other" }));
  await assert.rejects(f.service.notification(event));
  assert.equal(f.stats().completeCount, 0);
  f.paid.leftFee = 50;
  await f.service.notification(event);
  await f.service.notification(event);
  assert.equal(f.stats().completeCount, 1);
  f.sql.close();
});
test("legacy points are review-only and private controllers require a real identity", async () => {
  const f = await fixture({ legacy: true });
  assert.equal((await f.service.quote(1, "order1234")).reason, "LEGACY");
  for (const path of [
    "/api/virtual-pay/orders",
    "/api/virtual-pay/orders/order1234/refund-quote",
    "/api/virtual-pay/orders/order1234/refund-status",
  ]) {
    const response = await handlePaymentRequest(
      new Request("https://test" + path),
      {},
      {},
      {},
      f.service,
    );
    assert.equal(response.status, 401);
  }
  const response = await handlePaymentRequest(
    new Request("https://test/api/virtual-pay/orders?before=0", {
      headers: { "x-user-id": "1" },
    }),
    {},
    {},
    {},
    f.service,
  );
  assert.equal(response.status, 400);
  f.sql.close();
});
test("refund XML requires unique fields, valid amounts, and rejects entities", () => {
  const parser = new WechatMessageMapper();
  const xml =
    "<xml><Event><![CDATA[xpay_refund_notify]]></Event><OpenId>o</OpenId><MchOrderId>order1234</MchOrderId><MchRefundId>Refund1234</MchRefundId><WxOrderId>w</WxOrderId><WxRefundId>r</WxRefundId><RefundFee>150</RefundFee><RetCode>0</RetCode></xml>";
  assert.equal(parser.parseRefundNotification(xml).fee, 150);
  assert.throws(() =>
    parser.parseRefundNotification(
      xml.replace("</xml>", "<RefundFee>150</RefundFee></xml>"),
    ),
  );
  assert.throws(() => parser.parseRefundNotification("<!DOCTYPE xml>" + xml));
});

test("refund window uses verified payment time and rejects the exact seven-day boundary", async () => {
  const paidTime = 1700000000;
  assert.equal(
    refundWindowOpen(paidTime, (paidTime + 604800) * 1000 - 1),
    true,
  );
  assert.equal(refundWindowOpen(paidTime, (paidTime + 604800) * 1000), false);
  assert.equal(refundWindowOpen(undefined), false);
  assert.equal(refundWindowOpen(paidTime, paidTime * 1000 - 1), false);
  const f = await fixture();
  f.paid.paidTime = Math.floor(Date.now() / 1000) - 604800;
  assert.equal((await f.service.quote(1, "order1234")).reason, "EXPIRED");
  await assert.rejects(
    f.service.request(1, "order1234", 150),
    (e) => e.status === 409 && /7天/.test(e.message),
  );
  assert.equal(f.stats().submissions, 0);
  assert.equal(f.stats().hold, null);
  f.sql.close();
});

test("a stale preparing request cannot mark a concurrently claimed refund failed",async()=>{
 const f=await fixture();
 const original=f.service.core.refundPoints.bind(f.service.core);
 f.service.core.refundPoints=async(action,userId,eventKey,refundId)=>{
  const result=await original(action,userId,eventKey,refundId);
  if(action==='reserve') {
   await f.refunds.claim('order1234',150,200,15000);
   await f.refunds.update('order1234','PROCESSING');
   return {...result,state:'COMPLETED'};
  }
  return result;
 };
 await f.service.request(1,'order1234',150);
 assert.equal((await f.refunds.find('order1234')).status,'PROCESSING');
 assert.equal(f.stats().submissions,0);assert.equal(f.stats().releaseCount,0);f.sql.close();
});
test("an already submitted refund can complete after the seven-day request deadline",async()=>{
 const f=await fixture();await f.service.request(1,'order1234',150);
 f.paid.paidTime=Math.floor(Date.now()/1000)-8*86400;f.paid.leftFee=50;f.setProviderStatus(8);
 await f.service.reconcile(1,'order1234');
 assert.equal((await f.refunds.find('order1234')).status,'SUCCEEDED');f.sql.close();
});
