import { apiError, jsonResponse } from "@dinqor/worker-runtime";
import { BlogPlanError, BlogPlanService } from "../services/blog-plan-service.js";
import { BlogPlanExecutionError } from "../services/blog-plan-execution-service.js";
import type { BlogPlanExecutionPort } from "../interfaces/blog-plan-execution-port.js";
import type { WorkerExecutionContextLike } from "@dinqor/worker-runtime";

const BASE_PATH = "/api/blog-plans";
const MAX_BODY_BYTES = 32 * 1024;

function success(data: unknown): Response {
  return jsonResponse({ code: 200, message: "success", data });
}

function positiveId(value: string): number | null {
  if (!/^[1-9]\d{0,17}$/.test(value)) return null;
  const id = Number(value);
  return Number.isSafeInteger(id) ? id : null;
}

function ownerId(request: Request): number | null {
  const raw = request.headers.get("x-user-id")?.trim() ?? "";
  if (!/^[1-9]\d{0,17}$/.test(raw)) return null;
  const id = Number(raw);
  return Number.isSafeInteger(id) ? id : null;
}

function pageNumber(raw: string | null, fallback: number): number {
  if (raw === null) return fallback;
  if (!/^\d+$/.test(raw)) return Number.NaN;
  const value = Number(raw);
  return Number.isSafeInteger(value) && value > 0 ? value : Number.NaN;
}

async function readBody(request: Request): Promise<Record<string, unknown> | Response> {
  const declared = request.headers.get("content-length");
  if (declared && (!/^\d+$/.test(declared) || Number(declared) > MAX_BODY_BYTES)) {
    return apiError(413, "计划请求载荷过大");
  }
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
        return apiError(413, "计划请求载荷过大");
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
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  try {
    const parsed: unknown = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
    return parsed && typeof parsed === "object" && !Array.isArray(parsed)
      ? parsed as Record<string, unknown> : apiError(400, "请求内容格式不正确");
  } catch {
    return apiError(400, "请求内容格式不正确");
  }
}

function errorResponse(error: unknown): Response {
  if (error instanceof BlogPlanError) return apiError(error.status, error.message);
  if (error instanceof BlogPlanExecutionError) return apiError(error.status, error.message);
  return apiError(503, "定时发文计划服务暂不可用");
}

export async function handleBlogPlanRequest(
  request: Request,
  service: BlogPlanService,
  execution?: BlogPlanExecutionPort,
  context?: WorkerExecutionContextLike,
): Promise<Response | null> {
  const url = new URL(request.url);
  if (!url.pathname.startsWith(`${BASE_PATH}/`) && url.pathname !== BASE_PATH) return null;
  const userId = ownerId(request);
  if (userId === null) return apiError(401, "未登录或登录已过期");

  try {
    if (url.pathname === `${BASE_PATH}/ai-generate` && request.method === "POST") {
      const body = await readBody(request);
      if (body instanceof Response) return body;
      return success(await service.aiGenerate(userId, body));
    }
    if (url.pathname === `${BASE_PATH}/topics/generate` && request.method === "POST") {
      const body = await readBody(request);
      if (body instanceof Response) return body;
      return success(await service.generateTopics(userId, body));
    }

    if (url.pathname === BASE_PATH && request.method === "GET") {
      const page = pageNumber(url.searchParams.get("page"), 1);
      const size = pageNumber(url.searchParams.get("size"), 10);
      if (!Number.isFinite(page) || !Number.isFinite(size)) return apiError(400, "分页参数无效");
      return success(await service.list(userId, page, size));
    }
    if (url.pathname === BASE_PATH && request.method === "POST") {
      const body = await readBody(request);
      if (body instanceof Response) return body;
      return success(await service.create(userId, body));
    }

    const runMatch = url.pathname.match(/^\/api\/blog-plans\/([1-9]\d{0,17})\/runs(?:\/([1-9]\d{0,17}))?$/);
    if (runMatch) {
      const planId = positiveId(runMatch[1]);
      if (!planId) return apiError(400, "计划 ID 无效");
      if (request.method === "GET" && runMatch[2]) {
        const runId = positiveId(runMatch[2]);
        return runId ? success(await service.runDetail(userId, planId, runId)) : apiError(400, "执行记录 ID 无效");
      }
      if (request.method === "GET") {
        const page = pageNumber(url.searchParams.get("page"), 1);
        const size = pageNumber(url.searchParams.get("size"), 10);
        if (!Number.isFinite(page) || !Number.isFinite(size)) return apiError(400, "分页参数无效");
        return success(await service.listRuns(userId, planId, page, size));
      }
      return null;
    }

    const itemMatch = url.pathname.match(/^\/api\/blog-plans\/([1-9]\d{0,17})(?:\/(status|next|trigger))?$/);
    if (!itemMatch) return null;
    const planId = positiveId(itemMatch[1]);
    if (!planId) return apiError(400, "计划 ID 无效");
    const action = itemMatch[2];
    if (request.method === "GET" && !action) return success(await service.detail(userId, planId));
    if (request.method === "GET" && action === "next") {
      const count = pageNumber(url.searchParams.get("count"), 1);
      if (!Number.isFinite(count)) return apiError(400, "预览次数无效");
      return success(await service.nextRun(userId, planId, count));
    }
    if (request.method === "PATCH" && action === "status") {
      const body = await readBody(request);
      if (body instanceof Response) return body;
      return success(await service.updateStatus(userId, planId, body.action));
    }
    if (request.method === "PATCH" && !action) {
      const body = await readBody(request);
      if (body instanceof Response) return body;
      return success(await service.update(userId, planId, body));
    }
    if (request.method === "DELETE" && !action) {
      await service.delete(userId, planId);
      return success(null);
    }
    if (request.method === "POST" && action === "trigger") {
      if (!execution) return apiError(503, "计划执行器尚未配置");
      const run = await execution.trigger(userId, planId);
      if (context) context.waitUntil(execution.executeManual(userId, planId, run.id));
      else await execution.executeManual(userId, planId, run.id);
      return success(run);
    }
    return null;
  } catch (error) {
    return errorResponse(error);
  }
}
