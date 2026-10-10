import { apiError, jsonResponse, type D1Binding } from "@dinqor/worker-runtime";
import type { IdentityAuthService } from "../services/identity-auth-service.js";
import { ProfileOverviewService } from "../services/profile-overview-service.js";
import { resolveCurrentUser, type IdentityAuthEnvironment } from "./identity-auth-controller.js";
import { userInfo } from "./identity-view.js";

export interface ProfileOverviewEnvironment extends IdentityAuthEnvironment {
  DB: D1Binding;
  CONTENT_AUTOMATION_SERVICE_URL?: string;
  CONTENT_AUTOMATION_SERVICE_TOKEN?: string;
}

function success(data: unknown): Response {
  return jsonResponse({ code: 200, message: "success", data });
}

export async function handleProfileOverviewRequest(
  request: Request,
  env: ProfileOverviewEnvironment,
  auth: IdentityAuthService,
  service: ProfileOverviewService,
): Promise<Response | null> {
  const url = new URL(request.url);
  if (request.method !== "GET" || url.pathname !== "/api/profile/overview") return null;
  const user = await resolveCurrentUser(request, env, auth);
  if (user instanceof Response) return user;
  const result = await service.get(user);
  if (result.kind === "unconfigured") return apiError(503, "个人博客统计服务尚未配置");
  if (result.kind === "unavailable") return apiError(503, "个人博客统计服务暂不可用");
  if (result.kind === "invalid_response") return apiError(503, "个人博客统计服务响应无效");
  return success({ user: userInfo(result.user), points: result.points, blogCount: result.blogCount });
}
