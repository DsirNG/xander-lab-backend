/** Offline workerd/SDK/D1 verification. This never contacts Alipay or spends money. */
import assert from "node:assert/strict";
import { generateKeyPairSync, sign, verify } from "node:crypto";
import { readFile } from "node:fs/promises";
import { createRequire } from "node:module";
import { resolve } from "node:path";
import { spawnSync } from "node:child_process";

const root=resolve(import.meta.dirname,"..");
const pnpm=process.env.npm_execpath;
if (!pnpm) throw new Error("Run through pnpm smoke:alipay");
const build=spawnSync(process.execPath,[pnpm,"exec","wrangler","deploy","--dry-run","--outdir",".wrangler/alipay-smoke"],{
  cwd:resolve(root,"services/payment"),encoding:"utf8",windowsHide:true,
});
if (build.status!==0) throw new Error("Payment Worker dry-run bundle failed");
const require=createRequire(import.meta.url);
const wranglerRequire=createRequire(require.resolve("wrangler/package.json"));
const {Miniflare,Log,LogLevel,convertV4MiniflareOptions}=wranglerRequire("miniflare");
// These keys are ephemeral local fixtures, not real provider credentials.
const merchant=generateKeyPairSync("rsa",{modulusLength:2048});
const gateway=generateKeyPairSync("rsa",{modulusLength:2048});
const appId="2026000000000001";
const sellerId="2088000000000001";
const granted=new Set();
let tradeNo;
let tamperResponse=false;
let outboundCount=0;
const mf=new Miniflare(convertV4MiniflareOptions({
  modules:true,scriptPath:resolve(root,"services/payment/.wrangler/alipay-smoke/index.js"),
  compatibilityDate:"2026-10-10",compatibilityFlags:["nodejs_compat"],
  d1Databases:["DB"],log:new Log(LogLevel.ERROR),
  bindings:{INTERNAL_SERVICE_TOKEN:"local-payment",CORE_SERVICE_URL:"https://core.test",CORE_SERVICE_TOKEN:"local-core",
    ALIPAY_ENABLED:"true",ALIPAY_ENVIRONMENT:"sandbox",ALIPAY_APP_ID:appId,ALIPAY_SELLER_ID:sellerId,
    ALIPAY_PRIVATE_KEY:merchant.privateKey.export({type:"pkcs1",format:"der"}).toString("base64"),
    ALIPAY_PUBLIC_KEY:gateway.publicKey.export({type:"spki",format:"der"}).toString("base64"),
    ALIPAY_RETURN_URL:"http://localhost:3000/payment/alipay/return",
    ALIPAY_PRODUCTS:JSON.stringify([{productId:"pro",tier:"PRO",priceCents:2400,durationDays:30}]),
  },
  serviceBindings:{CORE_SERVICE:async (request) => {
    assert.equal(new URL(request.url).pathname,"/internal/v1/payment/entitlements/grant");
    const input=await request.json();granted.add(input.eventKey);
    return Response.json({code:200,data:{firstGrant:true}});
  }},
  outboundService:async (request) => {
    outboundCount+=1;
    const url=new URL(request.url);
    assert.equal(url.hostname,"openapi-sandbox.dl.alipaydev.com");
    const params=new URLSearchParams(await request.text());
    url.searchParams.forEach((value,key) => params.set(key,value));
    const signature=params.get("sign");params.delete("sign");
    const canonical=[...params].sort(([a],[b]) => a<b?-1:1).map(([key,value]) => `${key}=${value}`).join("&");
    assert.equal(verify("RSA-SHA256",Buffer.from(canonical),merchant.publicKey,Buffer.from(signature,"base64")),true);
    const method=params.get("method");assert.equal(method,"alipay.trade.query");
    const data={code:"10000",out_trade_no:tradeNo,trade_no:"ALI_OFFLINE",total_amount:"24.00",trade_status:"TRADE_SUCCESS"};
    const raw=JSON.stringify(data);
    const signed=sign("RSA-SHA256",Buffer.from(raw),gateway.privateKey).toString("base64");
    return new Response(`{"alipay_trade_query_response":${tamperResponse?raw.replace("24.00","0.01"):raw},"sign":${JSON.stringify(signed)}}`,{
      headers:{"content-type":"application/json"},
    });
  },
}));

async function api(path,method="GET",body) {
  const response=await mf.dispatchFetch(`https://payment.test${path}`,{
    method,headers:{"x-dinqor-service-token":"local-payment","x-user-id":"42","idempotency-key":"offline-smoke-00001"},
    ...(body?{body:JSON.stringify(body)}:{}),
  });
  return {status:response.status,body:await response.json()};
}

try {
  const db=await mf.getD1Database("DB");
  const migration=await readFile(resolve(root,"services/payment/migrations/0002_alipay_webpay.sql"),"utf8");
  for (const statement of migration.split(";")) if (statement.trim()) await db.prepare(statement).run();
  const created=await api("/api/alipay/orders","POST",{productId:"pro"});
  assert.equal(created.status,200,"workerd checkout failed");
  assert.match(created.body.data.paymentHtml,/<form/);
  tradeNo=created.body.data.outTradeNo;
  tamperResponse=true;
  const tampered=await api(`/api/alipay/orders/${tradeNo}/reconcile`,"POST",{});
  assert.equal(tampered.status,503,"tampered provider response must fail SDK verification");
  assert.equal(granted.size,0);
  tamperResponse=false;
  const paid=await api(`/api/alipay/orders/${tradeNo}/reconcile`,"POST",{});
  assert.equal(paid.status,200,"signed query failed in workerd");
  assert.equal(paid.body.data.status,"DELIVERED");
  const params={app_id:appId,seller_id:sellerId,out_trade_no:tradeNo,trade_no:"ALI_OFFLINE",
    trade_status:"TRADE_SUCCESS",total_amount:"24.00",notify_id:"offline-notification",notify_type:"trade_status_sync",sign_type:"RSA2"};
  const canonical=Object.keys(params).filter((key) => key!=="sign_type").sort().map((key) => `${key}=${params[key]}`).join("&");
  params.sign=sign("RSA-SHA256",Buffer.from(canonical),gateway.privateKey).toString("base64");
  for(let replay=0;replay<2;replay++){
    const callback=await mf.dispatchFetch("https://payment.test/api/alipay/notify",{
      method:"POST",headers:{"x-dinqor-service-token":"local-payment","content-type":"application/x-www-form-urlencoded"},body:new URLSearchParams(params).toString(),
    });
    assert.equal(await callback.text(),"success");
  }
  assert.equal(granted.size,1);assert.equal(outboundCount,2);
  const row=await db.prepare("SELECT status FROM alipay_web_order WHERE out_trade_no=?").bind(tradeNo).first();
  assert.equal(row.status,"DELIVERED");
  console.log("Offline Alipay smoke passed: real SDK in workerd, D1 checkout, signed fetch query, tamper rejection, callback replay, idempotent Core grant. No real Alipay sandbox transaction was performed.");
} finally { await mf.dispose(); }
