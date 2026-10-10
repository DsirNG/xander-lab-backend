import { apiError, jsonResponse } from "@dinqor/worker-runtime";
import type { IdentityAuthService } from "../services/identity-auth-service.js";

export interface IdentityAuthEnvironment {
  JWT_SECRET?: string;
}

const LOGIN_EXPIRED_MESSAGE = "未登录或登录已过期";

function bearerToken(request: Request): string | null {
  const authorization = request.headers.get("authorization");
  if (!authorization?.startsWith("Bearer ")) return null;
  const token = authorization.slice(7).trim();
  return token || null;
}

export async function resolveCurrentUser(
  request: Request,
  env: IdentityAuthEnvironment,
  service: IdentityAuthService,
) {
  const token = bearerToken(request);
  if (!token) return apiError(401, LOGIN_EXPIRED_MESSAGE);

  const result = await service.authenticateAccessToken(token, env.JWT_SECRET);
  if (result.kind === "unavailable") return apiError(503, "身份服务尚未配置");
  if (result.kind === "invalid") return apiError(401, LOGIN_EXPIRED_MESSAGE);
  return result.user;
}

export async function validateAccessToken(
  request: Request,
  env: IdentityAuthEnvironment,
  service: IdentityAuthService,
): Promise<Response> {
  const token = bearerToken(request);
  if (!token) return jsonResponse({ code: 200, message: "success", data: false });
  const result = await service.authenticateAccessToken(token, env.JWT_SECRET);
  return jsonResponse({ code: 200, message: "success", data: result.kind === "authenticated" });
}

export function handleIdentityAuthRequest(
  request: Request,
  env: IdentityAuthEnvironment,
  service: IdentityAuthService,
): Promise<Response | null> | null {
  const url = new URL(request.url);
  if (request.method === "GET" && url.pathname === "/api/auth/validate") {
    return validateAccessToken(request, env, service);
  }
  return null;
}
