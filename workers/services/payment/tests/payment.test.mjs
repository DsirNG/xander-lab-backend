import assert from "node:assert/strict";
import test from "node:test";
import { handlePaymentRequest } from "../.test-build/controllers/payment-controller.js";
import { PaymentSettingsService } from "../.test-build/services/payment-settings-service.js";

const encryptionKey = Buffer.alloc(32, 7).toString("base64");

function createRepository() {
  let row = null;
  return {
    repository: {
      async findSettings() { return row ? { ...row } : null; },
      async saveSettings(input) {
        row = {
          pointsProductsJson: input.pointsProducts === undefined ? row?.pointsProductsJson ?? null : JSON.stringify(input.pointsProducts),
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
    },
    raw() { return row; },
  };
}

function enabledSettings(appKey = "live-app-key") {
  return {
    enabled: true,
    offerId: "offer-123",
    appKey,
    plus: { productId: "plus-month", priceCents: 200, durationDays: 30 },
    pro: { productId: "pro-month", priceCents: 400, durationDays: 30 },
    ultra: { productId: "ultra-month", priceCents: 1000, durationDays: 30 },
  };
}

test("Payment settings encrypt the AppKey, preserve it on blank updates, and serve the catalog", async () => {
  const { repository, raw } = createRepository();
  const service = new PaymentSettingsService(repository);
  const saved = await service.save(enabledSettings(), encryptionKey);
  assert.equal(saved.kind, "saved");
  assert.equal(saved.view.appKeyConfigured, true);
  assert.notEqual(raw().encryptedAppKey, "live-app-key");

  const catalog = await service.catalog();
  assert.equal(catalog.enabled, true);
  assert.deepEqual(catalog.products.map(({ productId, tier, goodsPrice }) => ({ productId, tier, goodsPrice })), [
    { productId: "plus-month", tier: "PLUS", goodsPrice: 200 },
    { productId: "pro-month", tier: "PRO", goodsPrice: 400 },
    { productId: "ultra-month", tier: "ULTRA", goodsPrice: 1000 },
  ]);
  assert.equal((await service.resolve(encryptionKey)).appKey, "live-app-key");

  const updated = await service.save({ ...enabledSettings(""), offerId: "offer-456" }, encryptionKey);
  assert.equal(updated.kind, "saved");
  assert.equal((await service.resolve(encryptionKey)).appKey, "live-app-key");
  assert.equal((await service.get()).offerId, "offer-456");
});

test("Payment settings reject incomplete enabled catalogs and invalid encryption keys", async () => {
  const { repository } = createRepository();
  const service = new PaymentSettingsService(repository);
  const noKey = await service.save(enabledSettings(), undefined);
  assert.deepEqual(noKey, { kind: "invalid", message: "微信加密密钥未配置" });
  const incomplete = await service.save({ ...enabledSettings(), offerId: "", appKey: "" }, encryptionKey);
  assert.deepEqual(incomplete, { kind: "invalid", message: "OfferID 不能为空" });
  assert.equal((await service.catalog()).enabled, false);
});

test("payment settings enforce fixed tier price, period and distinct points SKU",async()=>{
  const {repository}=createRepository(); const service=new PaymentSettingsService(repository);
  const input=enabledSettings();
  assert.equal((await service.save({...input,plus:{...input.plus,priceCents:1000}},encryptionKey)).kind,"invalid");
  assert.equal((await service.save({...input,pro:{...input.pro,durationDays:60}},encryptionKey)).kind,"invalid");
  assert.equal((await service.save({...input,pointsProductId:input.plus.productId},encryptionKey)).kind,"invalid");
});

test("Payment public catalog and admin settings use the Java response envelope and admin role", async () => {
  const { repository } = createRepository();
  const service = new PaymentSettingsService(repository);
  const env = { PAYMENT_SETTINGS_ENCRYPTION_KEY: encryptionKey };

  const denied = await handlePaymentRequest(
    new Request("https://payment.example/api/admin/virtual-pay-settings"), env, service,
  );
  assert.equal(denied.status, 403);

  const saved = await handlePaymentRequest(new Request("https://payment.example/api/admin/virtual-pay-settings", {
    method: "PUT",
    headers: { "content-type": "application/json", "x-user-role": "ADMIN" },
    body: JSON.stringify(enabledSettings()),
  }), env, service);
  assert.equal(saved.status, 200);
  assert.equal((await saved.json()).data.appKeyConfigured, true);

  const response = await handlePaymentRequest(
    new Request("https://payment.example/api/virtual-pay/products"), env, service,
  );
  assert.equal(response.status, 200);
  const payload = await response.json();
  assert.equal(payload.code, 200);
  assert.equal(payload.data.enabled, true);
  assert.equal(payload.data.products.length, 3);
  assert.equal(JSON.stringify(payload).includes("live-app-key"), false);
});

const cards = [10, 50, 100].map(points => ({productId:`POINTS_${points}`,name:`${points}积分卡`,priceCents:points*10,points,enabled:true}));

test("three points cards validate price, count, identifiers and preserve omitted configuration", async () => {
  const {repository}=createRepository(); const service=new PaymentSettingsService(repository);
  const input={...enabledSettings(),pointsProducts:cards};
  assert.equal((await service.save(input,encryptionKey)).kind,"saved");
  assert.deepEqual((await service.get()).pointsProducts,cards);
  assert.equal((await service.catalog()).products.filter(p=>p.kind==="points").length,3);
  await service.save({...enabledSettings(""),pointsProductId:"legacy"},encryptionKey);
  assert.deepEqual((await service.get()).pointsProducts,cards);
  for(const invalid of [null,{},[...cards,cards[0]], [{...cards[0],priceCents:1}], [{...cards[0],points:11}], [{...cards[0],points:10010,priceCents:100100}], [{...cards[0],enabled:1}], [{...cards[0],name:""}], [{...cards[0],productId:"bad-id"}], [{...cards[0],productId:input.plus.productId}], [cards[0],cards[0]]]) {
    assert.equal((await service.save({...input,enabled:false,pointsProducts:invalid},encryptionKey)).kind,"invalid");
  }
  await service.save({...input,pointsProducts:cards.map(p=>({...p,enabled:false}))},encryptionKey);
  assert.equal((await service.catalog()).products.filter(p=>p.kind==="points").length,0);
  await service.save({...input,pointsProducts:[]},encryptionKey);
  assert.deepEqual((await service.get()).pointsProducts,[]);
});

test("admin points configuration rejects malformed JSON values with HTTP 400",async()=>{
 const {repository}=createRepository();const service=new PaymentSettingsService(repository);
 for(const pointsProducts of [null,"wrong",[{}]]) {
  const response=await handlePaymentRequest(new Request("https://payment.example/api/admin/virtual-pay-settings",{method:"PUT",headers:{"content-type":"application/json","x-user-role":"ADMIN"},body:JSON.stringify({...enabledSettings(),pointsProducts})}),{PAYMENT_SETTINGS_ENCRYPTION_KEY:encryptionKey},service);
  assert.equal(response.status,400);assert.equal((await response.json()).code,400);
 }
});
