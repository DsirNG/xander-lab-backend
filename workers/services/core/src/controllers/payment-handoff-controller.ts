import { apiError, jsonResponse } from "@dinqor/worker-runtime";
import type { IdentityUser } from "../interfaces/identity-repository.js";
import type { PaymentHandoffService } from "../services/payment-handoff-service.js";

interface Dependencies {
  service: PaymentHandoffService;
  createQr(ticket: string): Promise<string | null>;
  resolveUser(request: Request): Promise<IdentityUser | Response>;
  rateLimit(request: Request): Promise<Response | null>;
}
const success = (data: unknown) => {
  const response = jsonResponse({ code: 200, message: "success", data });
  response.headers.set("Cache-Control", "no-store");
  return response;
};
const identifier = /^[A-Za-z0-9_-]{1,128}$/;

export async function handlePaymentHandoffRequest(request: Request, deps: Dependencies): Promise<Response | null> {
  const path = new URL(request.url).pathname;
  const match = path.match(/^\/api\/payment-handoffs(?:\/([a-f0-9]{32}))?$/);
  if (!match) return null;
  const user = await deps.resolveUser(request);
  if (user instanceof Response) return user;
  const ticket = match[1];
  if (request.method === "GET" && ticket) {
    const state = await deps.service.read(ticket, user.id);
    return state ? success(state) : apiError(404, "支付二维码已过期，或小程序与网页账号不一致");
  }
  if (request.method !== "POST") return apiError(405, "Method not allowed");
  const limited = await deps.rateLimit(request);
  if (limited) return limited;
  let body: Record<string, unknown>;
  try {
    const raw = await request.text();
    if (raw.length > 2048) return apiError(400, "Invalid payment request");
    const parsed: unknown = JSON.parse(raw);
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return apiError(400, "Invalid payment request");
    body = parsed as Record<string, unknown>;
  } catch { return apiError(400, "Invalid payment request"); }
  if (ticket) {
    if (typeof body.outTradeNo !== "string" || !identifier.test(body.outTradeNo)) return apiError(400, "Invalid order identifier");
    // This is only a hint. Clients must verify the owned order through the payment service.
    const state = await deps.service.attach(ticket, user.id, body.outTradeNo);
    return state ? success(state) : apiError(404, "支付二维码已过期，或小程序与网页账号不一致");
  }
  const quantity = body.quantity ?? 1;
  if (typeof body.productId !== "string" || !/^[A-Za-z0-9_-]{1,64}$/.test(body.productId)
    || !Number.isSafeInteger(quantity) || Number(quantity) < 1 || Number(quantity) > 1000
    || (body.outTradeNo != null && (typeof body.outTradeNo !== "string" || !identifier.test(body.outTradeNo)))) {
    return apiError(400, "Invalid product or quantity");
  }
  const created = await deps.service.create(user.id, body.productId, Number(quantity), (body.outTradeNo as string | undefined) ?? null);
  const qrCode = await deps.createQr(created.ticket);
  if (!qrCode) {
    await deps.service.delete(created.ticket);
    return apiError(503, "微信二维码服务暂不可用，请稍后重试");
  }
  return success({ ...created, qrCode, serverNow: Date.now() });
}
