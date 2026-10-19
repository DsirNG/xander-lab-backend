import assert from "node:assert/strict";
import { generateKeyPairSync, sign } from "node:crypto";
import { readFileSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";
import test from "node:test";
import { handleAlipayRequest } from "../.test-build/controllers/alipay-controller.js";
import { D1AlipayOrderMapper } from "../.test-build/mappers/d1-alipay-order-mapper.js";
import { AlipaySdkMapper } from "../.test-build/mappers/alipay-sdk-mapper.js";
import { request as transport } from "../.test-build/mappers/alipay-fetch-transport.js";
import { resolveAlipayConfiguration, SANDBOX_GATEWAY } from "../.test-build/services/alipay-configuration.js";
import { AlipayOrderService } from "../.test-build/services/alipay-order-service.js";

// Ephemeral test-only keys. Never use these as sandbox or production credentials.
const keys = generateKeyPairSync("rsa",{ modulusLength:2048 });
const config = {
  environment:"sandbox", appId:"2026000000000001",sellerId:"2088000000000001",
  privateKey:keys.privateKey.export({ type:"pkcs1",format:"der" }).toString("base64"),
  publicKey:keys.publicKey.export({ type:"spki",format:"der" }).toString("base64"),
  gateway:SANDBOX_GATEWAY,returnUrl:"http://localhost:3000/payment/alipay/return",
  products:[{ productId:"pro-month",tier:"PRO",priceCents:2400,durationDays:30 }],
};

function setup() {
  const sqlite = new DatabaseSync(":memory:");
  sqlite.exec(readFileSync(new URL("../migrations/0002_alipay_webpay.sql",import.meta.url),"utf8"));
  const db = {
    prepare(sql) {
      let bindings = [];
      return {
        bind(...values) { bindings = values; return this; },
        async first() { return sqlite.prepare(sql).get(...bindings) ?? null; },
        async run() { const result = sqlite.prepare(sql).run(...bindings); return { success:true,meta:{ changes:Number(result.changes) } }; },
      };
    },
    async batch(statements) {
      sqlite.exec("BEGIN");
      try { const result = []; for (const statement of statements) result.push(await statement.run()); sqlite.exec("COMMIT"); return result; }
      catch (error) { sqlite.exec("ROLLBACK"); throw error; }
    },
  };
  const repository = new D1AlipayOrderMapper(db);
  const events = new Set();
  const calls = [];
  let failure = false;
  let result = { trade_status:"WAIT_BUYER_PAY",total_amount:"24.00",trade_no:"ALI_TEST" };
  const provider = {
    page:() => "<form></form>", verifyNotification:() => true,
    async call(method,body) { calls.push({ method,body }); return { out_trade_no:body.out_trade_no,...result }; },
  };
  const service = new AlipayOrderService(repository, { async grantEntitlement(input) {
    if (failure) throw new Error("Core unavailable");
    events.add(input.eventKey);
  } },provider,config);
  return { repository,service,sqlite,events,calls,provider,setResult(value) { result=value; },failCore(value) { failure=value; } };
}

const create = (service,key="request-000000000001") => service.create(42,"pro-month",key);
const notification = (outTradeNo, overrides={}) => ({
  app_id:config.appId,seller_id:config.sellerId,sign_type:"RSA2",sign:"test",notify_id:"notify-1",
  notify_type:"trade_status_sync",trade_no:"ALI_TEST",out_trade_no:outTradeNo,
  trade_status:"TRADE_SUCCESS",total_amount:"24.00",...overrides,
});

test("Alipay configuration is disabled by default and refuses incomplete or cross-environment URLs", () => {
  assert.equal(resolveAlipayConfiguration({}),null);
  const env = { ALIPAY_ENABLED:"true",ALIPAY_ENVIRONMENT:"sandbox",ALIPAY_APP_ID:config.appId,
    ALIPAY_SELLER_ID:config.sellerId,ALIPAY_PRIVATE_KEY:config.privateKey,ALIPAY_PUBLIC_KEY:config.publicKey,
    ALIPAY_RETURN_URL:config.returnUrl,ALIPAY_PRODUCTS:JSON.stringify(config.products) };
  assert.equal(resolveAlipayConfiguration(env).gateway,SANDBOX_GATEWAY);
  assert.throws(() => resolveAlipayConfiguration({ ...env,ALIPAY_ENVIRONMENT:"production" }));
  assert.throws(() => resolveAlipayConfiguration({ ...env,ALIPAY_RETURN_URL:"https://site.test/missing" }));
  assert.throws(() => resolveAlipayConfiguration({ ...env,ALIPAY_PRODUCTS:JSON.stringify([{ ...config.products[0],priceCents:1.25 }]) }));
});

test("SDK pageExec generates a signed POST form with real return URL and exact server price", () => {
  const mapper = new AlipaySdkMapper(config);
  const html = mapper.page({ outTradeNo:"A"+"1".repeat(32),priceCents:2400,subject:"Test subscription" });
  assert.match(html,/<form[^>]+method="post"/i);
  assert.match(html,/alipay\.trade\.page\.pay/);
  assert.match(html,/FAST_INSTANT_TRADE_PAY/);
  assert.match(html,/24\.00/);
  assert.doesNotMatch(html,/notify_url|undefined|null/);
  assert.doesNotMatch(html,new RegExp(config.privateKey.slice(0,60)));
});

test("SDK notification verification rejects tampering and non-RSA2 signatures", () => {
  const mapper = new AlipaySdkMapper(config);
  const params = notification("A"+"2".repeat(32));
  const content = Object.keys(params).filter((key) => !["sign","sign_type"].includes(key)).sort().map((key) => `${key}=${params[key]}`).join("&");
  params.sign = sign("RSA-SHA256",Buffer.from(content),keys.privateKey).toString("base64");
  assert.equal(mapper.verifyNotification(params),true);
  assert.equal(mapper.verifyNotification({ ...params,total_amount:"0.01" }),false);
  assert.equal(mapper.verifyNotification({ ...params,sign_type:"RSA" }),false);
});

test("D1 checkout is idempotent, snapshots the catalog and hides other users' orders", async () => {
  const s=setup();
  const first=await create(s.service);
  const second=await create(s.service);
  assert.equal(second.outTradeNo,first.outTradeNo);
  assert.equal(s.sqlite.prepare("SELECT count(*) AS n FROM alipay_web_order").get().n,1);
  await assert.rejects(s.service.status(99,first.outTradeNo),(error) => error.status===404);
  assert.equal(first.priceCents,2400);
  assert.doesNotMatch(JSON.stringify(first),/privateKey|sellerId|userId/);
});

test("verified callback and query replay grant one durable entitlement", async () => {
  const s=setup();const order=await create(s.service);
  await s.service.notify(notification(order.outTradeNo));
  await s.service.notify(notification(order.outTradeNo));
  assert.equal((await s.service.reconcile(42,order.outTradeNo)).status,"DELIVERED");
  assert.equal(s.events.size,1);
  assert.equal(s.sqlite.prepare("SELECT count(*) AS n FROM alipay_web_notification").get().n,1);
});

test("failed Core grants do not acknowledge callbacks or lose retryable payment intent", async () => {
  const s=setup();const order=await create(s.service);s.failCore(true);
  await assert.rejects(s.service.notify(notification(order.outTradeNo)));
  assert.equal((await s.repository.find(order.outTradeNo)).status,"PAID");
  assert.equal(s.sqlite.prepare("SELECT count(*) AS n FROM alipay_web_notification").get().n,0);
  s.failCore(false);await s.service.reconcile(42,order.outTradeNo);
  assert.equal(s.events.size,1);
  assert.equal((await s.repository.find(order.outTradeNo)).status,"DELIVERED");
});

test("notifications reject app/seller/amount/trade mismatch and never deliver refund events", async () => {
  const s=setup();const order=await create(s.service);
  for (const overrides of [{app_id:"other"},{seller_id:"other"},{total_amount:"0.01"},{trade_no:""}]) {
    await assert.rejects(s.service.notify(notification(order.outTradeNo,overrides)));
  }
  await s.service.notify(notification(order.outTradeNo,{ out_biz_no:"refund-1",refund_fee:"24.00" }));
  assert.equal(s.events.size,0);
  assert.equal((await s.repository.find(order.outTradeNo)).status,"PENDING");
});

test("unknown and pending query statuses stay pending; mismatched amounts cannot grant", async () => {
  const s=setup();const order=await create(s.service);
  assert.equal((await s.service.reconcile(42,order.outTradeNo)).status,"PENDING");
  s.setResult({trade_status:"UNRECOGNIZED",trade_no:"ALI_TEST",total_amount:"24.00"});
  assert.equal((await s.service.reconcile(42,order.outTradeNo)).status,"PENDING");
  s.setResult({trade_status:"TRADE_SUCCESS",trade_no:"ALI_TEST",total_amount:"24.01"});
  await assert.rejects(s.service.reconcile(42,order.outTradeNo));assert.equal(s.events.size,0);
});

test("close queries first and refuses a payment confirmed during reconciliation", async () => {
  const s=setup();const order=await create(s.service);
  s.setResult({trade_status:"TRADE_SUCCESS",trade_no:"ALI_TEST",total_amount:"24.00"});
  await assert.rejects(s.service.close(42,order.outTradeNo),(error) => error.status===409);
  assert.equal(s.calls.filter((call) => call.method==="alipay.trade.close").length,0);
});

test("full refund retries reuse the durable amount/key and require confirmed provider status", async () => {
  const s=setup();const order=await create(s.service);await s.service.notify(notification(order.outTradeNo));
  s.setResult({ trade_no:"ALI_TEST",fund_change:"N",refund_fee:"24.00" });
  assert.equal((await s.service.refund(order.outTradeNo)).status,"PENDING");
  await s.service.refund(order.outTradeNo);
  assert.deepEqual(s.calls[0].body,s.calls[1].body);
  assert.equal(s.calls[0].body.refund_amount,"24.00");
  s.setResult({trade_no:"ALI_TEST",out_request_no:`R${order.outTradeNo}`,refund_amount:"24.00",refund_status:"REFUND_SUCCESS"});
  const refunded=await s.service.queryRefund(order.outTradeNo);
  assert.equal(refunded.status,"SUCCEEDED");assert.equal(refunded.entitlementReviewRequired,true);
  await s.service.notify(notification(order.outTradeNo));assert.equal(s.events.size,1);
  assert.equal((await s.repository.find(order.outTradeNo)).status,"REFUNDED");
});

test("controllers enforce identity/admin role, strict amounts and fail-text callback retries", async () => {
  const s=setup();const get=() => s.service;
  const anonymous=await handleAlipayRequest(new Request("https://pay.test/api/alipay/products"),get);
  assert.equal(anonymous.status,401);
  const invalid=await handleAlipayRequest(new Request("https://pay.test/api/alipay/orders",{
    method:"POST",headers:{"x-user-id":"42","idempotency-key":"request-000000000001"},
    body:JSON.stringify({productId:"pro-month",priceCents:1}),
  }),get);assert.equal(invalid.status,400);
  const admin=await handleAlipayRequest(new Request("https://pay.test/api/admin/alipay/orders/A"+"1".repeat(32)+"/refund",{
    method:"POST",headers:{"x-user-id":"42"},
  }),get);assert.equal(admin.status,403);
  const disabled=await handleAlipayRequest(new Request("https://pay.test/api/admin/alipay/orders/A"+"1".repeat(32)+"/refund",{
    method:"POST",headers:{"x-user-id":"42","x-user-role":"ADMIN"},
  }),get);assert.equal(disabled.status,409);
  const callback=await handleAlipayRequest(new Request("https://pay.test/api/alipay/notify",{
    method:"POST",headers:{"content-type":"application/x-www-form-urlencoded"},body:"app_id=x&app_id=y",
  }),get);assert.equal(await callback.text(),"fail");
});

test("Workers fetch transport bounds hosts, redirects and response bodies", async () => {
  await assert.rejects(transport("https://attacker.test/gateway.do",{}));
  const original=globalThis.fetch;
  globalThis.fetch=async (url,options) => {
    assert.equal(new URL(url).hostname,"openapi-sandbox.dl.alipaydev.com");
    assert.equal(options.redirect,"manual");assert.equal(options.body.get("a"),"b");
    return new Response("signed-json",{ headers:{trace_id:"safe-id"} });
  };
  try {
    const result=await transport(SANDBOX_GATEWAY,{method:"POST",data:{a:"b"},dataType:"text",timeout:8000,headers:{}});
    assert.equal(result.data,"signed-json");assert.equal(result.headers.trace_id,"safe-id");
    globalThis.fetch=async () => new Response("x".repeat(65537));
    await assert.rejects(transport(SANDBOX_GATEWAY,{method:"POST",data:{},dataType:"text",timeout:8000,headers:{}}));
  } finally { globalThis.fetch=original; }
});
