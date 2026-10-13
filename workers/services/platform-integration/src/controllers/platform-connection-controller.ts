import { apiError, jsonResponse } from "@dinqor/worker-runtime";
import type { PlatformConnectionService } from "../services/platform-connection-service.js";
import { PlatformCredentialError } from "../services/platform-credential-service.js";

export async function handlePlatformConnectionRequest(request: Request, service: PlatformConnectionService): Promise<Response | null> {
  const match = new URL(request.url).pathname.match(/^\/api\/publishing\/(csdn|juejin)\/connection\/test$/);
  if (!match || request.method !== "POST") return null;
  const owner = request.headers.get("x-user-id");
  if (!owner || !/^[1-9]\d{0,17}$/.test(owner) || !Number.isSafeInteger(Number(owner))) {
    return apiError(401, "未登录或登录已过期");
  }
  try {
    const response = jsonResponse({ code: 200, message: "success", data: await service.testConnection(Number(owner), match[1] as "csdn" | "juejin") });
    response.headers.set("cache-control", "no-store");
    return response;
  } catch (error) {
    return error instanceof PlatformCredentialError ? apiError(error.status, error.message)
      : apiError(503, "平台连接验证服务暂不可用");
  }
}
