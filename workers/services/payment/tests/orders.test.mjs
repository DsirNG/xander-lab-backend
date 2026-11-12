import assert from "node:assert/strict";
import { createHash, createHmac } from "node:crypto";
import test from "node:test";
import { handlePaymentRequest } from "../.test-build/controllers/payment-controller.js";
import { PaymentCoreError } from "../.test-build/interfaces/payment-core-port.js";
import { CorePaymentMapper } from "../.test-build/mappers/core-payment-mapper.js";
import { WechatMessageMapper } from "../.test-build/mappers/wechat-message-mapper.js";
import { PaymentOrderService } from "../.test-build/services/payment-order-service.js";
import { PaymentSettingsService } from "../.test-build/services/payment-settings-service.js";

const encryptionKey = Buffer.alloc(32, 9).toString("base64");

function enabledSettings() {
  return {
    enabled: true,
    offerId: "offer-123",
    appKey: "application-key",
    plus: { productId: "plus-month", priceCents: 200, durationDays: 30 },
    pro: { productId: "pro-month", priceCents: 400, durationDays: 30 },
    ultra: { productId: "ultra-month", priceCents: 1000, durationDays: 30 },
  };
}

function setup(overrides = {}) {
  let settingsRow = null;
  const settingsRepo = {
    async findSettings() { return settingsRow ? { ...settingsRow } : null; },
    async saveSettings(input) {
      settingsRow = {
        pointsProductsJson: input.pointsProducts === undefined ? settingsRow?.pointsProductsJson ?? null : JSON.stringify(input.pointsProducts),
          pointsProductId: input.pointsProductId ?? "",
        enabled: input.enabled ? 1 : 0,
        offerId: input.offerId,
        encryptedAppKey: input.encryptedAppKey,
        plusProductId: input.plus.productId,
        plusPriceCents: input.plus.priceCents,
        plusDurationDays: input.plus.durationDays,
        proProductId: input.pro.productId,
        proPriceCents: input.pro.priceCents,
        proDurationDays: input.pro.durationDays,
        ultraProductId: input.ultra.productId,
        ultraPriceCents: input.ultra.priceCents,
        ultraDurationDays: input.ultra.durationDays,
        updatedAt: "2026-10-10T00:00:00.000Z",
      };
      return true;
    },
  };
  const records = new Map();
  const orderRepo = {
    async findPayable(userId, productId, quantity) {
      return [...records.values()].find(o => o.userId === userId && o.productId === productId && o.quantity === quantity && o.status === 'PENDING' && Date.parse(o.createdAt) + 900000 > Date.now()) ?? null;
    },
    async insertPending(input) {
      if ([...records.values()].some(o => o.userId === input.userId && o.productId === input.productId && o.quantity === input.quantity && o.status === 'PENDING' && Date.parse(o.createdAt) + 900000 > Date.now())) return false;
      records.set(input.outTradeNo, { id: records.size + 1, ...input, wxOrderId: null,
        status: "PENDING", createdAt: new Date().toISOString(), paidAt: null, deliveredAt: null });
      return true;
    },
    async findOwnedOrder(userId, tradeNo) {
      const order = records.get(tradeNo);
      return order?.userId === userId ? { ...order } : null;
    },
    async findOrder(tradeNo) { return records.get(tradeNo) ?? null; },
    async markDelivered() { return true; },
  };
  orderRepo.markDelivered = async (id, wxOrderId) => {
    const order = [...records.values()].find((candidate) => candidate.id === id);
    if (!order || order.status !== "PENDING") return false;
    order.wxOrderId = wxOrderId;
    order.status = "DELIVERED";
    order.deliveredAt = "2026-10-10T00:00:00.000Z";
    return true;
  };
  let grantCount = 0;
  const core = {
    async subscription() { return overrides.subscription ?? {tier:"FREE",active:false,tierExpiresAt:null}; },
    async grantPoints(input) { grantCount += 1; overrides.onPoints?.(input); },
    async resolveWechatIdentity(userId, loginCode) {
      if (overrides.identityError) throw overrides.identityError;
      return { openid: `openid-${userId}`, sessionKey: `session-${loginCode}` };
    },
    async resolveWechatCredential() { return { appId: "app-id", appSecret: "app-secret" }; },
    async grantEntitlement() { grantCount += 1; },
  };
  const settings = new PaymentSettingsService(settingsRepo);
  const env = { PAYMENT_SETTINGS_ENCRYPTION_KEY: encryptionKey, WECHAT_VIRTUAL_PAY_MESSAGE_TOKEN: "notify-token" };
  const wechat = { async queryOrder() { return overrides.queryResult ?? { paid: false, wxOrderId: null, paidFee: 0 }; } };
  const orders = new PaymentOrderService(orderRepo, settings, core, wechat, new WechatMessageMapper(), env);
  return { settings, orders, records, env, grantCount: () => grantCount };
}

test("Payment creates a pending D1 order and returns both WeChat HMAC signatures", async () => {
  const { settings, orders, records } = setup();
  await settings.save(enabledSettings(), encryptionKey);
  const payData = await orders.createOrder(42, "pro-month", "login-1");
  const signData = JSON.parse(payData.signData);
  assert.equal(payData.mode, "short_series_goods");
  assert.equal(signData.offerId, "offer-123");
  assert.equal(signData.productId, "pro-month");
  assert.equal(signData.goodsPrice, 400);
  assert.equal(signData.attach, payData.outTradeNo);
  assert.equal(payData.paySig, createHmac("sha256", "application-key")
    .update(`requestVirtualPayment&${payData.signData}`).digest("hex"));
  assert.equal(payData.signature, createHmac("sha256", "session-login-1")
    .update(payData.signData).digest("hex"));
  assert.equal(records.get(payData.outTradeNo).userId, 42);
  assert.equal(records.get(payData.outTradeNo).status, "PENDING");
  assert.equal(records.get(payData.outTradeNo).openid, "openid-42");
});

test("only active subscribers can buy permanent points, in server-priced ten-point units",async()=>{
  const denied=setup();
  await denied.settings.save({...enabledSettings(),pointsProductId:"points-ten"},encryptionKey);
  await assert.rejects(denied.orders.createOrder(42,"points-ten","login",2),e=>e.status===403);
  let points;
  const paid=setup({subscription:{tier:"PRO",active:true},queryResult:{paid:true,wxOrderId:"wx-points",paidFee:300},onPoints:i=>{points=i;}});
  await paid.settings.save({...enabledSettings(),pointsProductId:"points-ten"},encryptionKey);
  for(const qty of [0,-1,1.5,1001,NaN]) await assert.rejects(paid.orders.createOrder(42,"points-ten","login",qty),e=>e.status===400);
  const order=await paid.orders.createOrder(42,"points-ten","login",3);
  const signed=JSON.parse(order.signData);
  assert.equal(signed.goodsPrice,100); assert.equal(signed.buyQuantity,3);
  assert.equal(paid.records.get(order.outTradeNo).points,30);
  await paid.orders.reconcile(42,order.outTradeNo);
  await paid.orders.reconcile(42,order.outTradeNo);
  assert.equal(paid.grantCount(),1); assert.equal(points.points,30);
});

test("an active plan cannot be repurchased or downgraded; upgrade pays full new price",async()=>{
  const {settings,orders}=setup({subscription:{tier:"PRO",active:true}});
  await settings.save(enabledSettings(),encryptionKey);
  for(const product of ["plus-month","pro-month"]) await assert.rejects(orders.createOrder(42,product,"login"),e=>e.status===409);
  await assert.rejects(orders.createOrder(42,"ultra-month","login",2),e=>e.status===400);
  const order=await orders.createOrder(42,"ultra-month","login");
  assert.equal(JSON.parse(order.signData).goodsPrice,1000);
});

test("Payment rejects missing products and does not create orders for another user's lookup", async () => {
  const { settings, orders, records } = setup();
  await settings.save(enabledSettings(), encryptionKey);
  await assert.rejects(orders.createOrder(42, "unconfigured-product", "login"), /不存在或未启用/);
  const created = await orders.createOrder(42, "plus-month", "login");
  await assert.rejects(orders.status(99, created.outTradeNo), (error) => error.status === 404);
  const status = await orders.status(42, created.outTradeNo);
  assert.deepEqual(status, {
    outTradeNo: created.outTradeNo, status: "PENDING", tier: "PLUS", deliveredAt: null,
    expiresAt: new Date(Date.parse(records.get(created.outTradeNo).createdAt) + 900000).toISOString(), serverNow: status.serverNow,
  });
});

test("Payment maps Core identity rejection to a client error and service outage to 503", async () => {
  const rejected = setup({ identityError: new PaymentCoreError(400, "微信支付身份校验失败") });
  await rejected.settings.save(enabledSettings(), encryptionKey);
  await assert.rejects(rejected.orders.createOrder(42, "plus-month", "bad"), (error) => error.status === 400);

  const unavailable = setup({ identityError: new PaymentCoreError(503, "身份服务暂不可用") });
  await unavailable.settings.save(enabledSettings(), encryptionKey);
  await assert.rejects(unavailable.orders.createOrder(42, "plus-month", "code"), (error) => error.status === 503);
});

test("Payment reconcile grants a verified paid order once and leaves pending orders untouched", async () => {
  const pending = setup();
  await pending.settings.save(enabledSettings(), encryptionKey);
  const pendingOrder = await pending.orders.createOrder(42, "plus-month", "login");
  assert.equal((await pending.orders.reconcile(42, pendingOrder.outTradeNo)).status, "PENDING");
  assert.equal(pending.grantCount(), 0);

  const paid = setup({ queryResult: { paid: true, wxOrderId: "wx-123", paidFee: 200 } });
  await paid.settings.save(enabledSettings(), encryptionKey);
  const paidOrder = await paid.orders.createOrder(42, "plus-month", "login");
  assert.equal((await paid.orders.reconcile(42, paidOrder.outTradeNo)).status, "DELIVERED");
  assert.equal(paid.grantCount(), 1);
  assert.equal((await paid.orders.reconcile(42, paidOrder.outTradeNo)).status, "DELIVERED");
  assert.equal(paid.grantCount(), 1);
});

test("Payment reconcile rejects paid-fee mismatches and non-owners", async () => {
  const setupWithMismatch = setup({ queryResult: { paid: true, wxOrderId: "wx-123", paidFee: 199 } });
  await setupWithMismatch.settings.save(enabledSettings(), encryptionKey);
  const order = await setupWithMismatch.orders.createOrder(42, "plus-month", "login");
  await assert.rejects(setupWithMismatch.orders.reconcile(42, order.outTradeNo), (error) => error.status === 409);
  assert.equal(setupWithMismatch.grantCount(), 0);
  await assert.rejects(setupWithMismatch.orders.reconcile(99, order.outTradeNo), (error) => error.status === 404);
});

test("Payment notification parser and delivery are signed, matched, and idempotent", async () => {
  const { settings, orders, records, grantCount, env } = setup();
  await settings.save(enabledSettings(), encryptionKey);
  const created = await orders.createOrder(42, "plus-month", "login");
  const xml = `<xml><Event><![CDATA[xpay_goods_deliver_notify]]></Event><OpenId><![CDATA[openid-42]]></OpenId><OutTradeNo><![CDATA[${created.outTradeNo}]]></OutTradeNo><MchOrderNo><![CDATA[wx-456]]></MchOrderNo><ProductId><![CDATA[plus-month]]></ProductId><Quantity>1</Quantity><Env>0</Env></xml>`;
  await orders.deliverNotification(xml);
  await orders.deliverNotification(xml);
  assert.equal(records.get(created.outTradeNo).status, "DELIVERED");
  assert.equal(grantCount(), 1);
  const badOpenId = xml.replace("openid-42", "openid-other");
  await assert.rejects(orders.deliverNotification(badOpenId), (error) => error.status === 400);

  const sorted = [env.WECHAT_VIRTUAL_PAY_MESSAGE_TOKEN, "123", "nonce"].sort().join("");
  const validSignature = createHash("sha1").update(sorted).digest("hex");
  assert.equal(await orders.verifyNotificationSignature(validSignature, "123", "nonce"), true);
  assert.equal(await orders.verifyNotificationSignature("0".repeat(40), "123", "nonce"), false);
  await assert.rejects(orders.deliverNotification("<!DOCTYPE xml><xml></xml>"), (error) => error.status === 400);
});

test("Payment callback routes support URL verification and XML acknowledgement", async () => {
  const { settings, orders, env } = setup();
  await settings.save(enabledSettings(), encryptionKey);
  const signature = createHash("sha1").update([env.WECHAT_VIRTUAL_PAY_MESSAGE_TOKEN, "123", "nonce"].sort().join("")).digest("hex");
  const verify = await handlePaymentRequest(new Request(
    `https://payment.example/api/virtual-pay/notify?signature=${signature}&timestamp=123&nonce=nonce&echostr=verified`,
  ), env, settings, orders);
  assert.equal(await verify.text(), "verified");
  const bad = await handlePaymentRequest(new Request("https://payment.example/api/virtual-pay/notify?signature=bad"), env, settings, orders);
  assert.equal(await bad.text(), "invalid signature");
  const callback = await handlePaymentRequest(new Request(
    `https://payment.example/api/virtual-pay/notify?signature=${signature}&timestamp=123&nonce=nonce`,
    { method: "POST", headers: { "content-type": "application/xml" }, body: "<xml></xml>" },
  ), env, settings, orders);
  assert.equal(callback.headers.get("content-type"), "application/xml; charset=utf-8");
  assert.match(await callback.text(), /<ErrCode>1<\/ErrCode>/);

  const created = await orders.createOrder(42, "plus-month", "login");
  const xml = `<xml><Event>xpay_goods_deliver_notify</Event><OpenId>openid-42</OpenId><OutTradeNo>${created.outTradeNo}</OutTradeNo><WxOrderId>wx-route</WxOrderId><ProductId>plus-month</ProductId><Quantity>1</Quantity><Env>0</Env></xml>`;
  const successfulCallback = await handlePaymentRequest(new Request(
    `https://payment.example/api/virtual-pay/notify?signature=${signature}&timestamp=123&nonce=nonce`,
    { method: "POST", headers: { "content-type": "text/xml; charset=utf-8" }, body: xml },
  ), env, settings, orders);
  assert.match(await successfulCallback.text(), /<ErrCode>0<\/ErrCode>/);
});

test("Payment create-order Controller requires Gateway identity and preserves the response envelope", async () => {
  const { settings, orders, env } = setup();
  await settings.save(enabledSettings(), encryptionKey);
  const request = new Request("https://payment.example/api/virtual-pay/orders", {
    method: "POST",
    headers: { "content-type": "application/json", "x-user-id": "42" },
    body: JSON.stringify({ productId: "plus-month", loginCode: "one-time" }),
  });
  const response = await handlePaymentRequest(request, env, settings, orders);
  const payload = await response.json();
  assert.equal(response.status, 200);
  assert.equal(payload.code, 200);
  assert.equal(payload.data.mode, "short_series_goods");

  const anonymous = await handlePaymentRequest(new Request(request.url, {
    method: "POST", body: JSON.stringify({ productId: "plus-month", loginCode: "code" }),
  }), env, settings, orders);
  assert.equal(anonymous.status, 401);
});

test("Payment Core mapper uses protected HTTPS contracts for identity, credentials, and grants", async () => {
  const requests = [];
  const mapper = new CorePaymentMapper({
    CORE_SERVICE_URL: "https://core.example",
    CORE_SERVICE_TOKEN: "core-service-secret",
    CORE_SERVICE: {
      async fetch(request) {
        requests.push(request);
        if (request.url.endsWith("/identity/resolve")) {
          return Response.json({ code: 200, data: { openid: "openid-42", sessionKey: "session-key" } });
        }
        if (request.url.endsWith("/wechat/credentials")) {
          return Response.json({ code: 200, data: { appId: "app-id", appSecret: "app-secret" } });
        }
        return Response.json({ code: 200, data: { accepted: true } });
      },
    },
  });

  assert.deepEqual(await mapper.resolveWechatIdentity(42, "one-time-code"), {
    openid: "openid-42", sessionKey: "session-key",
  });
  assert.deepEqual(await mapper.resolveWechatCredential(), { appId: "app-id", appSecret: "app-secret" });
  await mapper.grantEntitlement({
    userId: 42, tier: "PRO", durationDays: 30, eventKey: "virtualpay:trade-1",
  });
  assert.equal(requests.length, 3);
  assert.equal(requests[0].headers.get("x-dinqor-service-token"), "core-service-secret");
  assert.deepEqual(await requests[0].json(), { userId: 42, loginCode: "one-time-code" });
  assert.deepEqual(await requests[2].json(), {
    userId: 42, tier: "PRO", durationDays: 30, eventKey: "virtualpay:trade-1",
  });
});

test("Payment Core mapper maps identity rejection and refuses cross-account HTTP", async () => {
  const rejected = new CorePaymentMapper({
    CORE_SERVICE_URL: "https://core.example",
    CORE_SERVICE_TOKEN: "token",
    CORE_SERVICE: { async fetch() { return Response.json({ code: 400 }, { status: 400 }); } },
  });
  await assert.rejects(rejected.resolveWechatIdentity(42, "bad"), (error) => error.status === 400);

  const insecure = new CorePaymentMapper({
    CORE_SERVICE_URL: "http://core.example",
    CORE_SERVICE_TOKEN: "token",
  });
  await assert.rejects(insecure.resolveWechatIdentity(42, "code"), (error) => error.status === 503);
});

test("all three cards use server amounts and deliver immutable snapshots once after config removal",async()=>{
 for(const points of [10,50,100]) {
  let granted;
  const ctx=setup({subscription:{tier:"PLUS",active:true},queryResult:{paid:true,wxOrderId:`wx-${points}`,paidFee:points*10},onPoints:i=>{granted=i;}});
  const cards=[10,50,100].map(n=>({productId:`POINTS_${n}`,name:`${n}积分卡`,priceCents:n*10,points:n,enabled:true}));
  assert.equal((await ctx.settings.save({...enabledSettings(),pointsProducts:cards},encryptionKey)).kind,"saved");
  const response=await handlePaymentRequest(new Request("https://payment.example/api/virtual-pay/orders",{method:"POST",headers:{"content-type":"application/json","x-user-id":"42"},body:JSON.stringify({productId:`POINTS_${points}`,loginCode:"code",priceCents:1,points:999999})}),ctx.env,ctx.settings,ctx.orders);
  assert.equal(response.status,200);
  const order=(await response.json()).data;
  assert.equal(JSON.parse(order.signData).goodsPrice,points*10);
  assert.equal(ctx.records.get(order.outTradeNo).points,points);
  await assert.rejects(ctx.orders.createOrder(42,`POINTS_${points}`,"code",Math.floor(10000/points)+1),e=>e.status===400);
  await ctx.settings.save({...enabledSettings(),pointsProducts:[]},encryptionKey);
  await assert.rejects(ctx.orders.createOrder(42,`POINTS_${points}`,"code"),e=>e.status===400);
  await ctx.orders.reconcile(42,order.outTradeNo);await ctx.orders.reconcile(42,order.outTradeNo);
  assert.equal(granted.points,points);assert.equal(ctx.grantCount(),1);
 }
 const denied=setup();
 await denied.settings.save({...enabledSettings(),pointsProducts:[{productId:"POINTS_50",name:"50积分卡",points:50,priceCents:500,enabled:true}]},encryptionKey);
 await assert.rejects(denied.orders.createOrder(42,"POINTS_50","code"),e=>e.status===403);
});

 test("pending checkout reuses the original number with a fresh session signature, including concurrent creates", async () => {
  const { settings, orders, records } = setup();
  await settings.save(enabledSettings(), encryptionKey);
  const [first, second] = await Promise.all([orders.createOrder(42, 'plus-month', 'one'), orders.createOrder(42, 'plus-month', 'two')]);
  assert.equal(first.outTradeNo, second.outTradeNo);
  assert.equal(records.size, 1);
  const resumed = await orders.createOrder(42, 'plus-month', 'three', 1, first.outTradeNo);
  assert.equal(resumed.outTradeNo, first.outTradeNo);
  assert.notEqual(resumed.signature, first.signature);
  await assert.rejects(orders.createOrder(99, 'plus-month', 'x', 1, first.outTradeNo), e => e.status === 404);
  await assert.rejects(orders.createOrder(42, 'pro-month', 'x', 1, first.outTradeNo), e => e.status === 409);
  records.get(first.outTradeNo).createdAt = new Date(Date.now() - 900001).toISOString();
  assert.equal((await orders.status(42, first.outTradeNo)).status, 'EXPIRED');
  await assert.rejects(orders.createOrder(42, 'plus-month', 'x', 1, first.outTradeNo), e => e.status === 409);
  const replacement = await orders.createOrder(42, 'plus-month', 'four');
  assert.notEqual(replacement.outTradeNo, first.outTradeNo);
 });
 test("verified late payment is delivered even when the local checkout deadline passed", async () => {
  const { settings, orders, records, grantCount } = setup({queryResult:{paid:true,wxOrderId:'late',paidFee:200}});
  await settings.save(enabledSettings(), encryptionKey);
  const order = await orders.createOrder(42, 'plus-month', 'one');
  records.get(order.outTradeNo).createdAt = new Date(Date.now() - 900001).toISOString();
  assert.equal((await orders.reconcile(42, order.outTradeNo)).status, 'DELIVERED');
  assert.equal(grantCount(), 1);
  await assert.rejects(orders.createOrder(42, 'plus-month', 'x', 1, order.outTradeNo), e => e.status === 409);
 });
