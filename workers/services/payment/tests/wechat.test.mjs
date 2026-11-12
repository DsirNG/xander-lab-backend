import assert from "node:assert/strict";
import { createHmac } from "node:crypto";
import test from "node:test";
import { PaymentProviderError, WechatVirtualPayMapper } from "../.test-build/mappers/wechat-virtual-pay-mapper.js";

test("WeChat virtual-payment mapper signs query_order and recognizes paid states", async () => {
  const originalFetch = globalThis.fetch;
  const requests = [];
  let status = 2;
  let credentialReads = 0;
  globalThis.fetch = async (input, init = {}) => {
    const url = new URL(input.toString());
    requests.push({ url, init });
    if (url.pathname === "/cgi-bin/token") {
      credentialReads += 1;
      assert.equal(url.searchParams.get("appid"), "app-id");
      assert.equal(url.searchParams.get("secret"), "app-secret");
      return Response.json({ access_token: "access-token", expires_in: 7200 });
    }
    assert.equal(url.pathname, "/xpay/query_order");
    const body = init.body.toString();
    assert.equal(body, JSON.stringify({ openid: "openid-1", env: 0, order_id: "trade-1" }));
    assert.equal(url.searchParams.get("pay_sig"), createHmac("sha256", "app-key")
      .update(`/xpay/query_order&${body}`).digest("hex"));
    assert.equal(url.searchParams.get("access_token"), "access-token");
    return Response.json({ errcode: 0, order: { status, wx_order_id: "wx-1", paid_fee: 500 } });
  };
  try {
    const mapper = new WechatVirtualPayMapper({}, {
      async resolveWechatCredential() { return { appId: "app-id", appSecret: "app-secret" }; },
    });
    assert.deepEqual(await mapper.queryOrder("openid-1", "trade-1", "app-key"), {
      paid: true, wxOrderId: "wx-1", paidFee: 500, status: 2, orderType: undefined, leftFee: undefined, paidTime: undefined,
    });
    status = 4;
    assert.equal((await mapper.queryOrder("openid-1", "trade-1", "app-key")).paid, true);
    status = 1;
    assert.equal((await mapper.queryOrder("openid-1", "trade-1", "app-key")).paid, false);
    assert.equal(credentialReads, 1);
    assert.equal(requests.length, 4);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("WeChat virtual-payment mapper rejects non-HTTPS and non-WeChat hosts", async () => {
  const mapper = new WechatVirtualPayMapper({ WECHAT_VIRTUAL_PAY_API_BASE_URL: "http://api.weixin.qq.com" }, {
    async resolveWechatCredential() { return { appId: "app-id", appSecret: "app-secret" }; },
  });
  await assert.rejects(mapper.queryOrder("openid-1", "trade-1", "app-key"), PaymentProviderError);
});

test("refund_order signs the exact body and retains server refund metadata", async()=>{
 const originalFetch=globalThis.fetch;
 const input={openid:'openid-1',order_id:'order1234',refund_order_id:'Refund1234',left_fee:200,refund_fee:150,biz_meta:'order1234',refund_reason:'3',req_from:'2',env:0};
 globalThis.fetch=async(url,init={})=>{
  const endpoint=new URL(url);
  if(endpoint.pathname==='/cgi-bin/token')return Response.json({access_token:'access-token',expires_in:7200});
  assert.equal(endpoint.pathname,'/xpay/refund_order');
  assert.equal(init.body,JSON.stringify(input));
  assert.equal(endpoint.searchParams.get('pay_sig'),createHmac('sha256','app-key').update('/xpay/refund_order&'+init.body).digest('hex'));
  assert.equal(init.redirect,'manual');assert.ok(init.signal);
  return Response.json({errcode:0,refund_wx_order_id:'wx-refund'});
 };
 try {
  const mapper=new WechatVirtualPayMapper({}, {async resolveWechatCredential(){return {appId:'app-id',appSecret:'app-secret'}}});
  assert.deepEqual(await mapper.refundOrder(input,'app-key'),{accepted:true,code:0,wxRefundId:'wx-refund'});
 } finally {globalThis.fetch=originalFetch;}
});
test("query_order carries original payment time, channel and remaining fee for refund eligibility", async()=>{
 const originalFetch=globalThis.fetch;
 globalThis.fetch=async(url)=>new URL(url).pathname==='/cgi-bin/token'
  ?Response.json({access_token:'access-token',expires_in:7200})
  :Response.json({errcode:0,order:{status:4,order_type:0,paid_time:1700000000,paid_fee:200,left_fee:150,wx_order_id:'wx-order'}});
 try {
  const mapper=new WechatVirtualPayMapper({}, {async resolveWechatCredential(){return {appId:'app-id',appSecret:'app-secret'}}});
  const result=await mapper.queryOrder('openid','order1234','app-key');
  assert.equal(result.paidTime,1700000000);assert.equal(result.orderType,0);assert.equal(result.leftFee,150);
 } finally {globalThis.fetch=originalFetch;}
});
