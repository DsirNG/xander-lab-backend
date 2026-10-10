import { apiError, jsonResponse } from "@dinqor/worker-runtime";
import { PlatformCredentialError } from "../services/platform-credential-service.js";
import type { JuejinCatalogService } from "../services/juejin-catalog-service.js";

function mapError(error: unknown): Response {
  return error instanceof PlatformCredentialError
    ? apiError(error.status, error.message)
    : apiError(503, "掘金目录同步暂不可用");
}

export async function handleJuejinCatalogRequest(
  request: Request,
  service: JuejinCatalogService,
): Promise<Response | null> {
  const path = new URL(request.url).pathname;
  if (path === "/api/publishing/juejin/catalog/sync" && request.method === "POST") {
    const owner = request.headers.get("x-user-id");
    if (!owner || !/^[1-9]\d{0,17}$/.test(owner) || !Number.isSafeInteger(Number(owner))) {
      return apiError(401, "未登录或登录已过期");
    }
    try {
      await service.sync(Number(owner));
      return jsonResponse({ code: 200, message: "success", data: null });
    } catch (error) {
      return mapError(error);
    }
  }

  const sync = path.match(/^\/internal\/platform-integration\/platform-credentials\/([1-9]\d{0,17})\/juejin\/catalog\/sync$/);
  if (!sync || request.method !== "POST") return null;

  const userId = Number(sync[1]);
  if (!Number.isSafeInteger(userId)) return apiError(400, "凭证属主参数非法");
  try {
    await service.sync(userId);
    return jsonResponse({ synced: true });
  } catch (error) {
    return mapError(error);
  }
}
