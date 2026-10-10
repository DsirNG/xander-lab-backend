import { createHash, createHmac, randomInt } from "node:crypto";
import { readFile } from "node:fs/promises";
import { spawnSync } from "node:child_process";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

const workersDirectory = resolve(fileURLToPath(new URL("..", import.meta.url)));
const coreDirectory = resolve(workersDirectory, "services/core");
const paymentDirectory = resolve(workersDirectory, "services/payment");
const gatewayUrl = process.env.GATEWAY_URL || "http://127.0.0.1:8787";
const userId = randomInt(900_000, 1_100_000);
const username = `payment-smoke-${userId}`;
const tradeNo = `SMOKE-${userId}`;
const callbackEventKey = `virtualpay:${tradeNo}`;

function readVars(text) {
  return Object.fromEntries(text.split(/\r?\n/)
    .filter((line) => line && !line.startsWith("#"))
    .map((line) => {
      const separator = line.indexOf("=");
      return [line.slice(0, separator), JSON.parse(line.slice(separator + 1))];
    }));
}

function encode(value) {
  return Buffer.from(JSON.stringify(value)).toString("base64url");
}

function runD1(directory, database, statement) {
  const pnpmEntry = process.env.npm_execpath;
  if (!pnpmEntry) throw new Error("Run this smoke check through `pnpm smoke:payment`.");
  const result = spawnSync(process.execPath, [pnpmEntry,
    "exec", "wrangler", "d1", "execute", database, "--local", "--command", statement,
  ], {
    cwd: directory,
    encoding: "utf8",
    windowsHide: true,
  });
  if (result.status !== 0) {
    throw new Error(`Local Core D1 smoke setup failed (${result.error?.message ?? result.status ?? "spawn"}).`);
  }
}

async function api(path, token) {
  return fetch(new URL(path, gatewayUrl), { headers: { authorization: `Bearer ${token}` } });
}

async function coreInternal(path, body, serviceToken) {
  return fetch(`http://127.0.0.1:8788${path}`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "x-dinqor-service-token": serviceToken,
    },
    body: JSON.stringify(body),
  });
}

const coreVars = readVars(await readFile(resolve(coreDirectory, ".dev.vars"), "utf8"));
const paymentVars = readVars(await readFile(resolve(paymentDirectory, ".dev.vars"), "utf8"));
const grantKey = `virtualpay:smoke-core-${userId}`;
const header = encode({ alg: "HS256", typ: "JWT" });
const payload = encode({
  sub: String(userId),
  exp: Math.floor(Date.now() / 1000) + 300,
  type: "access",
  role: "ADMIN",
  ver: 0,
});
const signingInput = `${header}.${payload}`;
const token = `${signingInput}.${createHmac("sha256", coreVars.JWT_SECRET).update(signingInput).digest("base64url")}`;

try {
  runD1(coreDirectory, "dinqor-core-local", `INSERT INTO sys_user(id, username, nickname, email, role, status) VALUES (${userId}, '${username}', 'Payment Smoke', '${username}@example.test', 'ADMIN', 1)`);
  runD1(paymentDirectory, "dinqor-payment-local", `INSERT INTO virtual_pay_order(out_trade_no, user_id, openid, product_id, tier, goods_price, quantity, duration_days, status) VALUES ('${tradeNo}', ${userId}, 'openid-smoke-${userId}', 'smoke-plus', 'PLUS', 1200, 1, 30, 'PENDING')`);
  const settingsResponse = await api("/api/admin/virtual-pay-settings", token);
  const settings = await settingsResponse.json();
  if (!settingsResponse.ok || settings.code !== 200 || typeof settings.data.appKeyConfigured !== "boolean") {
    throw new Error("Payment admin settings route failed through the Gateway");
  }
  if (JSON.stringify(settings).includes("encryptedAppKey")) {
    throw new Error("Payment settings response exposed encrypted secret material");
  }

  const catalogResponse = await api("/api/virtual-pay/products", token);
  const catalog = await catalogResponse.json();
  if (!catalogResponse.ok || catalog.code !== 200 || !Array.isArray(catalog.data.products)) {
    throw new Error("Payment product catalog route failed through the Gateway");
  }

  const grantBody = { userId, tier: "PRO", durationDays: 30, eventKey: grantKey };
  const firstGrantResponse = await coreInternal("/internal/v1/payment/entitlements/grant", grantBody,
    coreVars.INTERNAL_SERVICE_TOKEN);
  const firstGrant = await firstGrantResponse.json();
  if (!firstGrantResponse.ok || firstGrant.data?.firstGrant !== true) {
    throw new Error(`Core did not accept the first idempotent Payment entitlement (HTTP ${firstGrantResponse.status}, firstGrant=${String(firstGrant.data?.firstGrant)})`);
  }
  const duplicateResponse = await coreInternal("/internal/v1/payment/entitlements/grant", grantBody,
    coreVars.INTERNAL_SERVICE_TOKEN);
  const duplicate = await duplicateResponse.json();
  if (!duplicateResponse.ok || duplicate.data?.firstGrant !== false) {
    throw new Error("Core applied a duplicate Payment entitlement event");
  }
  const accountResponse = await api("/api/auth/me", token);
  const account = await accountResponse.json();
  if (!accountResponse.ok || account.data?.tier !== "PRO") {
    throw new Error("Core did not apply the Payment entitlement to the account");
  }

  const timestamp = String(Math.floor(Date.now() / 1000));
  const nonce = `smoke-${userId}`;
  const signature = createHash("sha1").update([paymentVars.WECHAT_VIRTUAL_PAY_MESSAGE_TOKEN, timestamp, nonce].sort().join("")).digest("hex");
  const notification = `<xml><Event>xpay_goods_deliver_notify</Event><OpenId>openid-smoke-${userId}</OpenId><OutTradeNo>${tradeNo}</OutTradeNo><MchOrderNo>wx-smoke-${userId}</MchOrderNo><ProductId>smoke-plus</ProductId><Quantity>1</Quantity><Env>0</Env></xml>`;
  const callbackUrl = `/api/virtual-pay/notify?signature=${signature}&timestamp=${timestamp}&nonce=${nonce}`;
  async function deliverCallback() {
    const callback = await fetch(new URL(callbackUrl, gatewayUrl), {
      method: "POST",
      headers: { "content-type": "application/xml" },
      body: notification,
    });
    const callbackBody = await callback.text();
    return { ok: callback.ok && callbackBody.includes("<ErrCode>0</ErrCode>"), status: callback.status, body: callbackBody };
  }
  let delivered = false;
  let lastDelivery;
  for (let attempt = 0; attempt < 3 && !delivered; attempt += 1) {
    lastDelivery = await deliverCallback();
    delivered = lastDelivery.ok;
  }
  if (!delivered) throw new Error(`Payment notification delivery did not succeed after bounded retries (HTTP ${lastDelivery?.status}, response ${lastDelivery?.body})`);
  const duplicateDelivery = await deliverCallback();
  if (!duplicateDelivery.ok) throw new Error(`Duplicate Payment notification was not acknowledged (HTTP ${duplicateDelivery.status}, response ${duplicateDelivery.body})`);
  const statusResponse = await api(`/api/virtual-pay/orders/${tradeNo}`, token);
  const orderStatus = await statusResponse.json();
  if (!statusResponse.ok || orderStatus.data?.status !== "DELIVERED") {
    throw new Error("Payment callback did not mark the order as delivered");
  }
  const tierAfterPlus = await api("/api/auth/me", token);
  const accountAfterPlus = await tierAfterPlus.json();
  if (accountAfterPlus.data?.tier !== "PRO") {
    throw new Error("A lower-tier Payment grant downgraded the existing Core entitlement");
  }
  console.log("Payment smoke passed (Gateway routing, settings/catalog reads, Core idempotent grant, signed callback replay, and owner-scoped order status).");
} finally {
  runD1(coreDirectory, "dinqor-core-local", `DELETE FROM payment_entitlement_grant WHERE event_key IN ('${grantKey}', '${callbackEventKey}')`);
  runD1(paymentDirectory, "dinqor-payment-local", `DELETE FROM virtual_pay_order WHERE out_trade_no = '${tradeNo}'`);
  runD1(coreDirectory, "dinqor-core-local", `DELETE FROM sys_user WHERE id = ${userId}`);
}
