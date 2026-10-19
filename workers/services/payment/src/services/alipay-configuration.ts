import type { AlipayConfiguration, AlipayProduct } from "../interfaces/alipay-port.js";
import type { PaymentEnvironment } from "../interfaces/payment-environment.js";

export const SANDBOX_GATEWAY = "https://openapi-sandbox.dl.alipaydev.com/gateway.do";
export const PRODUCTION_GATEWAY = "https://openapi.alipay.com/gateway.do";

function endpoint(value: string | undefined, production: boolean, callback = false): string {
  const url = new URL(value ?? "");
  const local = ["localhost", "127.0.0.1", "[::1]"].includes(url.hostname);
  if (url.username || url.password || url.hash || url.search
    || (url.protocol !== "https:" && !(local && !production && !callback && url.protocol === "http:"))
    || (production && local)) throw new Error("Invalid payment URL");
  return url.toString();
}

/** Missing configuration fails closed. Never infer production from an application ID. */
export function resolveAlipayConfiguration(env: PaymentEnvironment): AlipayConfiguration | null {
  if (env.ALIPAY_ENABLED !== "true") return null;
  if (env.ALIPAY_ENVIRONMENT !== "sandbox" && env.ALIPAY_ENVIRONMENT !== "production") {
    throw new Error("Explicit Alipay environment required");
  }
  const production = env.ALIPAY_ENVIRONMENT === "production";
  if (!/^\d{16}$/.test(env.ALIPAY_APP_ID ?? "") || !/^2088\d{12}$/.test(env.ALIPAY_SELLER_ID ?? "")
    || !env.ALIPAY_PRIVATE_KEY || !env.ALIPAY_PUBLIC_KEY) throw new Error("Incomplete payment configuration");
  // Pass the provider's original, unwrapped key fields directly to the official SDK.
  if (![env.ALIPAY_PRIVATE_KEY, env.ALIPAY_PUBLIC_KEY].every((key) => /^[A-Za-z0-9+/]+=*$/.test(key))) {
    throw new Error("Raw key material required");
  }
  const parsed: unknown = JSON.parse(env.ALIPAY_PRODUCTS ?? "[]");
  if (!Array.isArray(parsed) || parsed.length === 0 || parsed.length > 20) throw new Error("Invalid catalog");
  const products = parsed as AlipayProduct[];
  const ids = new Set<string>();
  for (const item of products) {
    if (!item || !/^[A-Za-z0-9_-]{1,64}$/.test(item.productId) || ids.has(item.productId)
      || !["PLUS", "PRO", "ULTRA"].includes(item.tier)
      || !Number.isSafeInteger(item.priceCents) || item.priceCents < 1 || item.priceCents > 10_000_000_000
      || !Number.isSafeInteger(item.durationDays) || item.durationDays < 1 || item.durationDays > 3660) {
      throw new Error("Invalid payment product");
    }
    ids.add(item.productId);
  }
  const returnUrl = endpoint(env.ALIPAY_RETURN_URL, production);
  if (new URL(returnUrl).pathname !== "/payment/alipay/return") throw new Error("Invalid return route");
  const notifyUrl = env.ALIPAY_NOTIFY_URL ? endpoint(env.ALIPAY_NOTIFY_URL, production, true) : undefined;
  if (production && !notifyUrl) throw new Error("Production notification URL required");
  if (notifyUrl && new URL(notifyUrl).pathname !== "/api/alipay/notify") throw new Error("Invalid notify route");
  return {
    environment: env.ALIPAY_ENVIRONMENT, appId: env.ALIPAY_APP_ID!, sellerId: env.ALIPAY_SELLER_ID!,
    privateKey: env.ALIPAY_PRIVATE_KEY, publicKey: env.ALIPAY_PUBLIC_KEY,
    gateway: production ? PRODUCTION_GATEWAY : SANDBOX_GATEWAY,
    returnUrl, ...(notifyUrl ? { notifyUrl } : {}), products,
  };
}

export function amountText(cents: number): string {
  return `${Math.floor(cents / 100)}.${String(cents % 100).padStart(2, "0")}`;
}

export function amountCents(value: unknown): number | null {
  if (typeof value !== "string" || !/^\d{1,9}(?:\.\d{1,2})?$/.test(value)) return null;
  const [whole, fraction = ""] = value.split(".");
  const cents = Number(whole) * 100 + Number(fraction.padEnd(2, "0"));
  return Number.isSafeInteger(cents) ? cents : null;
}
