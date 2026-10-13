import { apiError, jsonResponse } from "@dinqor/worker-runtime";
import type { PlatformConnectionService } from "../services/platform-connection-service.js";
import { PlatformCredentialError } from "../services/platform-credential-service.js";

export async function handlePlatformConnectionRequest(request: Request, service: PlatformConnectionService): Promise<Response | null> {
  const match = new URL(request.url).pathname.match(/^\/api\/publishing\/(csdn|juejin|wechat_mp)\/connection\/test$/);
  if (!match || request.method !== "POST") return null;
  const owner = request.headers.get("x-user-id");
  if (!owner || !/^[1-9]\d{0,17}$/.test(owner) || !Number.isSafeInteger(Number(owner))) {
    return apiError(401, "未登录或登录已过期");
  }
  try {
    let wechatProfile: { nickname: string; avatarUrl: string | null } | undefined;
    if (match[1] === "wechat_mp" && request.body) {
      let body: unknown;
      try { body = await request.json(); } catch { return apiError(400, "微信公众号账号资料格式不正确"); }
      if (!body || typeof body !== "object" || Array.isArray(body)) return apiError(400, "微信公众号账号资料格式不正确");
      const accountProfile = (body as Record<string, unknown>).accountProfile;
      if (!accountProfile || typeof accountProfile !== "object" || Array.isArray(accountProfile)) {
        return apiError(400, "微信公众号账号资料格式不正确");
      }
      const profile = accountProfile as Record<string, unknown>;
      if (typeof profile.nickname !== "string" || profile.nickname.trim().length === 0 || profile.nickname.length > 200
        || (profile.avatarUrl !== null && profile.avatarUrl !== undefined && typeof profile.avatarUrl !== "string")) {
        return apiError(400, "微信公众号账号资料格式不正确");
      }
      wechatProfile = { nickname: profile.nickname, avatarUrl: typeof profile.avatarUrl === "string" ? profile.avatarUrl : null };
    }
    const response = jsonResponse({ code: 200, message: "success", data: await service.testConnection(
      Number(owner), match[1] as "csdn" | "juejin" | "wechat_mp", wechatProfile,
    ) });
    response.headers.set("cache-control", "no-store");
    return response;
  } catch (error) {
    return error instanceof PlatformCredentialError ? apiError(error.status, error.message)
      : apiError(503, "平台连接验证服务暂不可用");
  }
}
