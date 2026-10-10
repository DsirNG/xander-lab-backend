import { apiError, jsonResponse } from "@dinqor/worker-runtime";
import type { IdentitySessionSettings, IdentitySessionService } from "../services/identity-session-service.js";
import { userInfo } from "./identity-view.js";

const LOGIN_EXPIRED_MESSAGE = "未登录或登录已过期";
const MAX_BODY_BYTES = 16 * 1024;
const MAX_TOKEN_BYTES = 8 * 1024;

function success(data: unknown): Response {
  return jsonResponse({ code: 200, message: "success", data });
}

async function readObject(request: Request): Promise<Record<string, unknown> | Response> {
  const declaredLength = request.headers.get("content-length");
  if (declaredLength && Number(declaredLength) > MAX_BODY_BYTES) return apiError(413, "请求内容过大");
  if (!request.body) return apiError(400, "请求内容格式不正确");
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let length = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      length += value.byteLength;
      if (length > MAX_BODY_BYTES) {
        await reader.cancel();
        return apiError(413, "请求内容过大");
      }
      chunks.push(value);
    }
  } catch {
    return apiError(400, "请求内容格式不正确");
  } finally {
    reader.releaseLock();
  }
  const bytes = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  try {
    const value: unknown = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
    return value && typeof value === "object" && !Array.isArray(value)
      ? value as Record<string, unknown>
      : apiError(400, "请求内容格式不正确");
  } catch {
    return apiError(400, "请求内容格式不正确");
  }
}

export async function refreshAccessToken(
  request: Request,
  settings: IdentitySessionSettings,
  service: IdentitySessionService,
): Promise<Response> {
  if (!settings.jwtSecret || new TextEncoder().encode(settings.jwtSecret).byteLength < 32) {
    return apiError(503, "身份服务尚未配置");
  }
  const body = await readObject(request);
  if (body instanceof Response) return body;
  const token = body.refreshToken;
  if (typeof token !== "string" || !token.trim() || new TextEncoder().encode(token).byteLength > MAX_TOKEN_BYTES) {
    return apiError(400, "refreshToken 不能为空或过长");
  }

  const result = await service.refresh(token, settings);
  if (result.kind === "unconfigured") return apiError(503, "身份服务尚未配置");
  if (result.kind === "invalid") return apiError(401, LOGIN_EXPIRED_MESSAGE);
  return success({
    accessToken: result.accessToken,
    refreshToken: result.refreshToken,
    tokenType: "Bearer",
    expiresIn: result.expiresIn,
    userInfo: userInfo(result.user),
    needsEmailBinding: !result.user.email,
    pendingBind: false,
    pendingBindToken: null,
  });
}

export async function logout(
  request: Request,
  settings: IdentitySessionSettings,
  service: IdentitySessionService,
): Promise<Response> {
  const authorization = request.headers.get("authorization");
  if (!authorization?.startsWith("Bearer ") || !settings.jwtSecret) return success(null);
  await service.logout(authorization.slice(7), settings.jwtSecret);
  return success(null);
}

export async function handleIdentitySessionRequest(
  request: Request,
  settings: IdentitySessionSettings,
  service: IdentitySessionService,
  enforceRefreshLimit: (request: Request) => Promise<Response | null>,
): Promise<Response | null> {
  const url = new URL(request.url);
  if (request.method === "POST" && url.pathname === "/api/auth/refresh") {
    const limited = await enforceRefreshLimit(request);
    return limited ?? refreshAccessToken(request, settings, service);
  }
  if (request.method === "POST" && url.pathname === "/api/auth/logout") {
    return logout(request, settings, service);
  }
  return null;
}
