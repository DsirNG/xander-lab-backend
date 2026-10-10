import { apiError, jsonResponse } from "@dinqor/worker-runtime";
import type { PaymentEnvironment } from "../interfaces/payment-environment.js";
import { PaymentOrderService, PaymentRequestError } from "../services/payment-order-service.js";
import { PaymentSettingsService } from "../services/payment-settings-service.js";

const MAX_SETTINGS_BYTES = 16 * 1024;
const MAX_NOTIFICATION_BYTES = 64 * 1024;
const XML_SUCCESS = "<xml><ErrCode>0</ErrCode><ErrMsg><![CDATA[success]]></ErrMsg></xml>";
const XML_FAILED = "<xml><ErrCode>1</ErrCode><ErrMsg><![CDATA[failed]]></ErrMsg></xml>";
type JsonObject = Record<string, unknown>;

function success(data: unknown): Response {
  return jsonResponse({ code: 200, message: "success", data });
}

function isObject(value: unknown): value is JsonObject {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function text(value: unknown): string | null {
  if (value === undefined || value === null) return "";
  if (typeof value !== "string") return null;
  return value.trim();
}

function product(value: unknown) {
  if (value === undefined || value === null) return { productId: "", priceCents: 0, durationDays: 30 };
  if (!isObject(value)) return null;
  const productId = text(value.productId);
  if (productId === null || productId.length > 100
    || typeof value.priceCents !== "number" || !Number.isSafeInteger(value.priceCents)
    || typeof value.durationDays !== "number" || !Number.isSafeInteger(value.durationDays)) return null;
  return { productId, priceCents: value.priceCents, durationDays: value.durationDays };
}

async function readObject(request: Request): Promise<JsonObject | Response> {
  const contentLength = request.headers.get("content-length");
  if (contentLength && Number(contentLength) > MAX_SETTINGS_BYTES) return apiError(413, "请求内容过大");
  let body: unknown;
  try {
    const raw = await request.text();
    if (new TextEncoder().encode(raw).byteLength > MAX_SETTINGS_BYTES) return apiError(413, "请求内容过大");
    body = JSON.parse(raw);
  } catch {
    return apiError(400, "请求内容格式不正确");
  }
  return isObject(body) ? body : apiError(400, "请求内容格式不正确");
}

export async function handlePaymentRequest(
  request: Request,
  env: PaymentEnvironment,
  service: PaymentSettingsService,
  orders: PaymentOrderService,
): Promise<Response | null> {
  const url = new URL(request.url);
  if (url.pathname === "/api/virtual-pay/products" && request.method === "GET") {
    return success(await service.catalog());
  }
  if (url.pathname === "/api/virtual-pay/orders" && request.method === "POST") {
    const userId = positiveUserId(request.headers.get("x-user-id"));
    if (!userId) return apiError(401, "未登录或登录已过期");
    const body = await readObject(request);
    if (body instanceof Response) return body;
    if (typeof body.productId !== "string" || !body.productId.trim() || body.productId.length > 100
      || typeof body.loginCode !== "string" || !body.loginCode.trim() || body.loginCode.length > 512) {
      return apiError(400, "商品ID或微信登录凭证格式不正确");
    }
    try {
      return success(await orders.createOrder(userId, body.productId.trim(), body.loginCode.trim()));
    } catch (error) {
      return error instanceof PaymentRequestError
        ? apiError(error.status, error.message)
        : apiError(503, "支付服务暂不可用");
    }
  }
  if (url.pathname === "/api/virtual-pay/notify" && request.method === "GET") {
    const valid = await orders.verifyNotificationSignature(
      url.searchParams.get("signature"), url.searchParams.get("timestamp"), url.searchParams.get("nonce"),
    );
    return new Response(valid ? url.searchParams.get("echostr") ?? "" : "invalid signature", {
      headers: { "content-type": "text/plain; charset=utf-8" },
    });
  }
  if (url.pathname === "/api/virtual-pay/notify" && request.method === "POST") {
    const valid = await orders.verifyNotificationSignature(
      url.searchParams.get("signature"), url.searchParams.get("timestamp"), url.searchParams.get("nonce"),
    );
    if (!valid || !isXmlContentType(request.headers.get("content-type"))) return xmlResponse(XML_FAILED);
    const xml = await readXmlBody(request);
    if (xml === null) return xmlResponse(XML_FAILED);
    try {
      await orders.deliverNotification(xml);
      return xmlResponse(XML_SUCCESS);
    } catch {
      return xmlResponse(XML_FAILED);
    }
  }
  const orderMatch = url.pathname.match(/^\/api\/virtual-pay\/orders\/([A-Za-z0-9_-]{1,128})$/);
  if (orderMatch && request.method === "GET") {
    const userId = positiveUserId(request.headers.get("x-user-id"));
    if (!userId) return apiError(401, "未登录或登录已过期");
    try {
      return success(await orders.status(userId, orderMatch[1]));
    } catch (error) {
      return error instanceof PaymentRequestError
        ? apiError(error.status, error.message)
        : apiError(503, "支付服务暂不可用");
    }
  }
  const reconcileMatch = url.pathname.match(/^\/api\/virtual-pay\/orders\/([A-Za-z0-9_-]{1,128})\/reconcile$/);
  if (reconcileMatch && request.method === "POST") {
    const userId = positiveUserId(request.headers.get("x-user-id"));
    if (!userId) return apiError(401, "未登录或登录已过期");
    try {
      return success(await orders.reconcile(userId, reconcileMatch[1]));
    } catch (error) {
      return error instanceof PaymentRequestError
        ? apiError(error.status, error.message)
        : apiError(503, "支付服务暂不可用");
    }
  }
  if (url.pathname !== "/api/admin/virtual-pay-settings" || !["GET", "PUT"].includes(request.method)) {
    return null;
  }
  if (request.headers.get("x-user-role") !== "ADMIN") return apiError(403, "无权限访问");
  if (request.method === "GET") return success(await service.get());

  const body = await readObject(request);
  if (body instanceof Response) return body;
  if (typeof body.enabled !== "boolean") return apiError(400, "enabled 必须为布尔值");
  const offerId = text(body.offerId);
  const appKey = text(body.appKey);
  if (offerId === null || offerId.length > 100) return apiError(400, "OfferID 格式不正确");
  if (appKey === null || appKey.length > 200) return apiError(400, "AppKey 过长");
  const plus = product(body.plus);
  const pro = product(body.pro);
  const ultra = product(body.ultra);
  if (!plus || !pro || !ultra) return apiError(400, "PLUS、PRO、ULTRA 商品配置格式不正确");

  const result = await service.save({ enabled: body.enabled, offerId, appKey, plus, pro, ultra },
    env.PAYMENT_SETTINGS_ENCRYPTION_KEY);
  if (result.kind === "invalid") return apiError(400, result.message);
  return result.kind === "saved" ? success(result.view) : apiError(503, "虚拟支付配置保存失败");
}

function positiveUserId(value: string | null): number | null {
  if (!value || !/^[1-9]\d{0,17}$/.test(value)) return null;
  const userId = Number(value);
  return Number.isSafeInteger(userId) ? userId : null;
}

function xmlResponse(body: string): Response {
  return new Response(body, { headers: { "content-type": "application/xml; charset=utf-8" } });
}

function isXmlContentType(value: string | null): boolean {
  return Boolean(value && /^\s*(?:application|text)\/xml\s*(?:;|$)/i.test(value));
}

async function readXmlBody(request: Request): Promise<string | null> {
  const contentLength = request.headers.get("content-length");
  if (contentLength && (!/^\d+$/.test(contentLength) || Number(contentLength) > MAX_NOTIFICATION_BYTES)) return null;
  if (!request.body) return null;
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > MAX_NOTIFICATION_BYTES) {
        await reader.cancel();
        return null;
      }
      chunks.push(value);
    }
    const bytes = new Uint8Array(total);
    let offset = 0;
    for (const chunk of chunks) {
      bytes.set(chunk, offset);
      offset += chunk.byteLength;
    }
    return new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  } catch {
    return null;
  } finally {
    reader.releaseLock();
  }
}
