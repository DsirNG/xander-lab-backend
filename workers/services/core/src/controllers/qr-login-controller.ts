import { apiError, jsonResponse } from "@dinqor/worker-runtime";
import type { IdentityUser } from "../interfaces/identity-repository.js";
import type { WechatLoginQrRepository } from "../interfaces/wechat-login-qr-repository.js";
import type { IdentitySessionService } from "../services/identity-session-service.js";
import { QR_LOGIN_TTL_SECONDS, QrLoginService } from "../services/qr-login-service.js";

const LOGIN_EXPIRED_MESSAGE = "未登录或登录已过期";

export interface QrLoginControllerDependencies {
  service: QrLoginService;
  qrImages: WechatLoginQrRepository;
  sessions: IdentitySessionService;
  resolveUser(request: Request): Promise<IdentityUser | Response>;
  createTokenResponse(user: IdentityUser): Promise<Response>;
  enforceRateLimit(request: Request, namespace: string, limit: number): Promise<Response | null>;
}

function success(data: unknown): Response {
  return jsonResponse({ code: 200, message: "success", data });
}

export async function handleQrLoginRequest(
  request: Request,
  dependencies: QrLoginControllerDependencies,
): Promise<Response | null> {
  const url = new URL(request.url);
  if (request.method === "POST" && url.pathname === "/api/auth/qr/create") {
    const limited = await dependencies.enforceRateLimit(request, "auth-qr-ip", 10);
    if (limited) return limited;
    const created = await dependencies.service.create();
    const qrCode = await dependencies.qrImages.createLoginQr(created.ticket);
    if (!qrCode) {
      await dependencies.service.delete(created.ticket);
      return apiError(503, "微信二维码服务暂不可用，请稍后重试");
    }
    return success({ ticket: created.ticket, qrCode, expiresIn: QR_LOGIN_TTL_SECONDS });
  }

  if (request.method === "GET" && url.pathname === "/api/auth/qr/status") {
    const ticket = url.searchParams.get("ticket") ?? "";
    if (!/^[a-f0-9]{32}$/.test(ticket)) return apiError(400, "QR ticket is invalid");
    return success(await dependencies.service.status(ticket));
  }

  if (request.method === "POST" && url.pathname === "/api/auth/qr/confirm") {
    const ticket = url.searchParams.get("ticket") ?? "";
    if (!/^[a-f0-9]{32}$/.test(ticket)) return apiError(400, "QR ticket is invalid");
    const user = await dependencies.resolveUser(request);
    if (user instanceof Response) return user;
    const confirmed = await dependencies.service.confirm(ticket, user.id);
    return confirmed
      ? success(null)
      : apiError(400, "QR code is expired or already confirmed");
  }

  if (request.method === "POST" && url.pathname === "/api/auth/qr/exchange") {
    const ticket = url.searchParams.get("ticket") ?? "";
    const exchangeCode = url.searchParams.get("exchangeCode") ?? "";
    if (!/^[a-f0-9]{32}$/.test(ticket) || !/^[a-f0-9]{48}$/.test(exchangeCode)) {
      return apiError(400, "Login credential is invalid or already used");
    }
    const userId = await dependencies.service.exchange(ticket, exchangeCode);
    if (userId === null) return apiError(400, "Login credential is invalid or already used");
    const user = await dependencies.sessions.findActiveUserById(userId);
    return user ? dependencies.createTokenResponse(user) : apiError(401, LOGIN_EXPIRED_MESSAGE);
  }
  return null;
}
