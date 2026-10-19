import { apiError, jsonResponse } from "@dinqor/worker-runtime";
import type { AlipayOrderService } from "../services/alipay-order-service.js";
import { PaymentRequestError } from "../services/payment-order-service.js";

const BASE = "/api/alipay";
const ADMIN = "/api/admin/alipay";
const TRADE = /^A[a-f0-9]{32}$/;

async function bodyText(request: Request): Promise<string> {
  if (!request.body || Number(request.headers.get("content-length")) > 16 * 1024) throw new Error("Invalid body");
  const reader = request.body.getReader();
  const decoder = new TextDecoder("utf-8",{ fatal: true });
  let size = 0;
  let text = "";
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > 16 * 1024) { await reader.cancel(); throw new Error("Body too large"); }
      text += decoder.decode(value,{ stream: true });
    }
    return text + decoder.decode();
  } finally { reader.releaseLock(); }
}

const plain = (value: string) => new Response(value,{ headers: { "content-type":"text/plain; charset=utf-8", "cache-control":"no-store" } });
const ok = (data: unknown) => jsonResponse({ code:200,message:"success",data });

/** Gateway authenticates the user; notifications independently verify provider signatures. */
export async function handleAlipayRequest(request: Request, getService: () => AlipayOrderService | null,
  refundsEnabled = false): Promise<Response | null> {
  const path = new URL(request.url).pathname;
  if (!path.startsWith(`${BASE}/`) && !path.startsWith(`${ADMIN}/`)) return null;
  const notify = path === `${BASE}/notify` && request.method === "POST";
  if (notify) {
    if (!/^application\/x-www-form-urlencoded(?:\s*;|$)/i.test(request.headers.get("content-type") ?? "")) return plain("fail");
    try {
      const service = getService();
      if (!service) return plain("fail");
      const form = new URLSearchParams(await bodyText(request));
      const params: Record<string,string> = {};
      let duplicated = false;
      form.forEach((value,key) => {
        if (Object.hasOwn(params,key)) duplicated = true;
        Object.defineProperty(params,key,{ value,enumerable:true,writable:true });
      });
      if (duplicated) return plain("fail");
      await service.notify(params);
      return plain("success");
    } catch { return plain("fail"); }
  }
  const rawUserId = request.headers.get("x-user-id");
  const userId = rawUserId && /^[1-9]\d{0,17}$/.test(rawUserId) ? Number(rawUserId) : 0;
  if (!Number.isSafeInteger(userId) || userId <= 0) return apiError(401,"未登录或登录已过期");
  const admin = path.startsWith(`${ADMIN}/`);
  if (admin && request.headers.get("x-user-role") !== "ADMIN") return apiError(403,"Access denied");
  try {
    const service = getService();
    if (!service) return path === `${BASE}/products` && request.method === "GET"
      ? ok({ enabled:false,products:[] }) : apiError(503,"Alipay payments are not configured");
    if (path === `${BASE}/products` && request.method === "GET") return ok(service.catalog());
    if (path === `${BASE}/orders` && request.method === "POST") {
      let body;
      try { body = JSON.parse(await bodyText(request)); } catch { return apiError(400,"Invalid payment request"); }
      const key = request.headers.get("idempotency-key");
      if (!body || typeof body !== "object" || Array.isArray(body)
        || typeof body.productId !== "string" || !/^[A-Za-z0-9_-]{1,64}$/.test(body.productId)
        || Object.keys(body).some((field) => field !== "productId")
        || !key || !/^[A-Za-z0-9_-]{16,100}$/.test(key)) return apiError(400,"Invalid product or idempotency key");
      return ok(await service.create(userId,body.productId,key));
    }
    const match = path.match(/^\/api\/(?:admin\/)?alipay\/orders\/([^/]+)(?:\/(reconcile|close|refund|refund-status))?$/);
    if (!match || !TRADE.test(match[1])) return apiError(404,"Payment route does not exist");
    const [,tradeNo,action] = match;
    if (admin) {
      if (action === "refund" && request.method === "POST") {
        if (!refundsEnabled) return apiError(409,"Refunds require an enabled subscription entitlement review process");
        return ok(await service.refund(tradeNo));
      }
      if (action === "refund-status" && request.method === "GET") return ok(await service.queryRefund(tradeNo));
    } else {
      if (!action && request.method === "GET") return ok(await service.status(userId,tradeNo));
      if (action === "reconcile" && request.method === "POST") return ok(await service.reconcile(userId,tradeNo));
      if (action === "close" && request.method === "POST") return ok(await service.close(userId,tradeNo));
    }
    return apiError(404,"Payment route does not exist");
  } catch (error) {
    return error instanceof PaymentRequestError ? apiError(error.status,error.message) : apiError(503,"Payment operation could not be confirmed; retry with the same request");
  }
}
