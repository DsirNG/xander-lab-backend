import { apiError, jsonResponse } from "@dinqor/worker-runtime";
import { BlogPlanError } from "../services/blog-plan-service.js";
import { BlogPlanSeriesService } from "../services/blog-plan-series-service.js";

const BASE = "/api/blog-plan-series";
const success = (data: unknown) =>
  jsonResponse({ code: 200, message: "success", data });
const owner = (request: Request) => {
  const value = Number(request.headers.get("x-user-id"));
  return Number.isSafeInteger(value) && value > 0 ? value : null;
};
const idValue = (value: string) => {
  const id = Number(value);
  return Number.isSafeInteger(id) && id > 0 ? id : null;
};
async function body(
  request: Request,
): Promise<Record<string, unknown> | Response> {
  const length = Number(request.headers.get("content-length") ?? 0);
  if (length > 64 * 1024) return apiError(413, "系列计划请求内容过大");
  try {
    const value: unknown = await request.json();
    return value && typeof value === "object" && !Array.isArray(value)
      ? (value as Record<string, unknown>)
      : apiError(400, "系列计划请求格式无效");
  } catch {
    return apiError(400, "系列计划请求格式无效");
  }
}

export async function handleBlogPlanSeriesRequest(
  request: Request,
  service: BlogPlanSeriesService,
): Promise<Response | null> {
  const url = new URL(request.url);
  if (url.pathname !== BASE && !url.pathname.startsWith(`${BASE}/`))
    return null;
  const userId = owner(request);
  if (userId === null) return apiError(401, "未登录或登录已过期");
  try {
    if (url.pathname === BASE && request.method === "GET")
      return success(await service.list(userId));
    if (url.pathname === BASE && request.method === "POST") {
      const value = await body(request);
      return value instanceof Response
        ? value
        : success(await service.createDraft(userId, value));
    }
    const match = url.pathname.match(
      /^\/api\/blog-plan-series\/([1-9]\d{0,17})(?:\/(activate|status))?$/,
    );
    if (!match) return null;
    const id = idValue(match[1]);
    if (!id) return apiError(400, "系列 ID 无效");
    if (!match[2] && request.method === "GET")
      return success(await service.detail(userId, id));
    if (!match[2] && request.method === "PATCH") {
      const value = await body(request);
      return value instanceof Response
        ? value
        : success(await service.updateDraft(userId, id, value));
    }
    if (match[2] === "activate" && request.method === "POST")
      return success(await service.activate(userId, id));
    if (match[2] === "status" && request.method === "PATCH") {
      const value = await body(request);
      return value instanceof Response
        ? value
        : success(await service.control(userId, id, value.action));
    }
    return null;
  } catch (error) {
    return error instanceof BlogPlanError
      ? apiError(error.status, error.message)
      : apiError(503, "系列计划服务暂不可用");
  }
}
