import assert from "node:assert/strict";
import { createHmac } from "node:crypto";
import test from "node:test";
import worker from "../.test-build/index.js";

const secret="alipay-gateway-test-secret-32-bytes-minimum";
function token(role="USER") {
  const encoded=(data) => Buffer.from(JSON.stringify(data)).toString("base64url");
  const data=`${encoded({alg:"HS256",typ:"JWT"})}.${encoded({sub:"42",type:"access",role,exp:Math.floor(Date.now()/1000)+60})}`;
  return `${data}.${createHmac("sha256",secret).update(data).digest("base64url")}`;
}
function setup() {
  const requests=[];
  const env={JWT_SECRET:secret,CORE_SERVICE_URL:"https://core.test",CORE_SERVICE_TOKEN:"test-core",
    CORE_SERVICE:{async fetch(){return Response.json({userId:42});}},
    PAYMENT_SERVICE_URL:"https://payment.test",PAYMENT_SERVICE_TOKEN:"test-payment",
    PAYMENT_SERVICE:{async fetch(request){requests.push(request);return new Response("success");}}};
  return {env,requests};
}

test("Alipay callback is the only anonymous payment route and forged identity is removed",async () => {
  const {env,requests}=setup();
  const callback=await worker.fetch(new Request("https://api.test/api/alipay/notify",{
    method:"POST",headers:{"x-user-id":"999","content-type":"application/x-www-form-urlencoded"},body:"signed=notification",
  }),env);
  assert.equal(callback.status,200);assert.equal(await callback.text(),"success");
  assert.equal(requests[0].headers.get("x-user-id"),null);
  assert.equal(requests[0].headers.get("x-dinqor-service-token"),"test-payment");
  for (const path of ["products","orders/A"+"1".repeat(32),"notify/extra"]) {
    const response=await worker.fetch(new Request(`https://api.test/api/alipay/${path}`),env);
    assert.ok([401,404].includes(response.status));
  }
  assert.equal(requests.length,1);
});

test("Alipay checkout propagates only the verified owner and stable idempotency key",async () => {
  const {env,requests}=setup();
  const response=await worker.fetch(new Request("https://api.test/api/alipay/orders",{
    method:"POST",headers:{authorization:`Bearer ${token()}`,"x-user-id":"999","idempotency-key":"request-00000000001"},body:"{}",
  }),env);
  assert.equal(response.status,200);assert.equal(requests[0].headers.get("x-user-id"),"42");
  assert.equal(requests[0].headers.get("idempotency-key"),"request-00000000001");
  const denied=await worker.fetch(new Request("https://api.test/api/admin/alipay/orders/A"+"1".repeat(32)+"/refund",{
    method:"POST",headers:{authorization:`Bearer ${token()}`},
  }),env);
  assert.equal(denied.status,403);
  assert.equal(requests.length,1);
});
