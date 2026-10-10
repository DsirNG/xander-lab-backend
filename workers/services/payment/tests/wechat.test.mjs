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
      paid: true, wxOrderId: "wx-1", paidFee: 500,
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
