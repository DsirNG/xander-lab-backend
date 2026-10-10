import { apiError, jsonResponse } from "@dinqor/worker-runtime";
import { BlogManagementError, BlogManagementService } from "../services/blog-management-service.js";

const MAX_REQUEST_BYTES = 4 * 1024 * 1024;

function success(data: unknown): Response {
  return jsonResponse({ code: 200, message: "success", data });
}

function userIdFromGateway(request: Request): number | null {
  const raw = request.headers.get("x-user-id")?.trim();
  if (!raw || !/^[1-9]\d{0,17}$/.test(raw)) return null;
  const userId = Number(raw);
  return Number.isSafeInteger(userId) ? userId : null;
}

function parseIntegerParameter(params: URLSearchParams, name: string, defaultValue: number): number | null {
  const raw = params.get(name);
  if (raw === null) return defaultValue;
  if (!/^-?\d+$/.test(raw)) return null;
  const value = Number(raw);
  return Number.isSafeInteger(value) ? value : null;
}

function parsePostId(raw: string): number | null {
  if (!/^[1-9]\d*$/.test(raw)) return null;
  const id = Number(raw);
  return Number.isSafeInteger(id) ? id : null;
}

async function readJson(request: Request): Promise<unknown> {
  const declaredLength = request.headers.get("content-length");
  if (declaredLength && (!/^\d+$/.test(declaredLength) || Number(declaredLength) > MAX_REQUEST_BYTES)) {
    throw new BlogManagementError(413, "文章请求载荷过大");
  }
  const reader = request.body?.getReader();
  if (!reader) throw new BlogManagementError(400, "请求体不能为空");
  const chunks: Uint8Array[] = [];
  let length = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      length += value.byteLength;
      if (length > MAX_REQUEST_BYTES) {
        await reader.cancel();
        throw new BlogManagementError(413, "文章请求载荷过大");
      }
      chunks.push(value);
    }
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
    return JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes)) as unknown;
  } catch {
    throw new BlogManagementError(400, "文章请求格式不正确");
  }
}

function errorResponse(error: unknown): Response {
  if (error instanceof BlogManagementError) return apiError(error.status, error.message);
  return apiError(503, "文章管理暂时不可用");
}

export async function handleBlogManagementRequest(
  request: Request,
  service: BlogManagementService,
): Promise<Response | null> {
  const { pathname, searchParams } = new URL(request.url);
  const userId = userIdFromGateway(request);

  if (request.method === "GET" && (
    pathname === "/api/blog/posts/mine" || /^\/api\/blog\/posts\/mine\/\d+$/.test(pathname)
    || pathname === "/api/blog/posts/publish-status"
    || pathname === "/api/blog/posts/publish-rhythm"
    || pathname === "/api/blog/posts/manage-stats"
  )) {
    if (userId === null) return apiError(401, "未登录或登录已过期");
    try {
      if (pathname === "/api/blog/posts/mine") {
        const page = parseIntegerParameter(searchParams, "page", 1);
        const size = parseIntegerParameter(searchParams, "size", 10);
        const status = searchParams.has("status") ? parseIntegerParameter(searchParams, "status", 0) : null;
        if (page === null || size === null || status === null && searchParams.has("status")) {
          return apiError(400, "Invalid page, size, or status");
        }
        return success(await service.listMine(userId, status, searchParams.get("search") ?? "", page, size));
      }
      const mineMatch = pathname.match(/^\/api\/blog\/posts\/mine\/(\d+)$/);
      if (mineMatch) {
        const postId = parsePostId(mineMatch[1]);
        if (postId === null) return apiError(400, "Invalid article ID");
        const post = await service.findMine(userId, postId);
        return post ? success(post) : apiError(404, "文章不存在");
      }
      if (pathname === "/api/blog/posts/publish-status") {
        const requestId = searchParams.get("requestId");
        if (!requestId?.trim()) return apiError(400, "requestId is required");
        const postId = await service.publishedPostId(userId, requestId);
        return success({ status: postId === null ? "pending" : "published", postId });
      }
      if (pathname === "/api/blog/posts/publish-rhythm") return success(await service.publishRhythm(userId));
      return success(await service.manageStats(userId));
    } catch (error) {
      return errorResponse(error);
    }
  }

  const statusMatch = pathname.match(/^\/api\/blog\/posts\/(\d+)\/status$/);
  const permanentMatch = pathname.match(/^\/api\/blog\/posts\/(\d+)\/permanent$/);
  const postMatch = pathname.match(/^\/api\/blog\/posts\/(\d+)$/);
  const matchesRoute = request.method === "PATCH" && statusMatch
    || request.method === "DELETE" && permanentMatch
    || (request.method === "PUT" || request.method === "DELETE") && postMatch;
  if (!matchesRoute) return null;
  if (userId === null) return apiError(401, "未登录或登录已过期");

  try {
    if (statusMatch && request.method === "PATCH") {
      const postId = parsePostId(statusMatch[1]);
      if (postId === null) return apiError(400, "Invalid article ID");
      return success(await service.updateStatus(userId, postId, await readJson(request)));
    }
    if (permanentMatch && request.method === "DELETE") {
      const postId = parsePostId(permanentMatch[1]);
      if (postId === null) return apiError(400, "Invalid article ID");
      await service.permanentlyDelete(userId, postId);
      return success(null);
    }
    if (postMatch) {
      const postId = parsePostId(postMatch[1]);
      if (postId === null) return apiError(400, "Invalid article ID");
      if (request.method === "DELETE") {
        await service.moveToTrash(userId, postId);
        return success(null);
      }
      return success(await service.update(userId, postId, await readJson(request)));
    }
  } catch (error) {
    return errorResponse(error);
  }
  return null;
}
