import { apiError, jsonResponse } from "@dinqor/worker-runtime";
import type { IdentityUser } from "../interfaces/identity-repository.js";
import type { NotificationDiagnosticService } from "../services/notification-diagnostic-service.js";

function success(data: unknown): Response {
  return jsonResponse({ code: 200, message: "success", data });
}

export async function handleAdminNotificationRequest(
  request: Request,
  service: NotificationDiagnosticService,
  resolveUser: (request: Request) => Promise<IdentityUser | Response>,
): Promise<Response | null> {
  const url = new URL(request.url);
  if (request.method !== "PATCH" || url.pathname !== "/api/admin/notifications/test-broadcast") return null;
  const operator = await resolveUser(request);
  if (operator instanceof Response) return operator;
  if (operator.role !== "ADMIN") return apiError(403, "无权访问");
  const result = await service.testBroadcast();
  if (result.kind === "sent") return success(result.recipients);
  if (result.kind === "unconfigured") return apiError(503, "通知广播服务尚未配置");
  if (result.kind === "invalid_response") return apiError(503, "通知广播服务响应无效");
  return apiError(503, "通知广播服务暂不可用");
}
