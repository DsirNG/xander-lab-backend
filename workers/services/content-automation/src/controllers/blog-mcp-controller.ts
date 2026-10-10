import { apiError, jsonResponse } from "@dinqor/worker-runtime";
import { BlogManagementError, BlogManagementService } from "../services/blog-management-service.js";
import { BlogPostCreationError, BlogPostCreationService } from "../services/blog-post-creation-service.js";
import { BlogMcpService } from "../services/blog-mcp-service.js";

const MAX_REQUEST_BYTES = 2 * 1024 * 1024 + 32 * 1024;

export async function handleBlogMcpRequest(
  request: Request,
  service: BlogMcpService,
): Promise<Response | null> {
  if (request.method !== "POST" || new URL(request.url).pathname !== "/internal/platform-integration/mcp/blog") return null;
  try {
    const body = await readBody(request);
    if (body instanceof Response) return body;
    if (!isObject(body) || !Number.isSafeInteger(body.userId) || typeof body.userId !== "number" || body.userId < 1) {
      return apiError(400, "文章属主参数非法");
    }
    const userId = body.userId;
    switch (body.operation) {
      case "list": {
        const page = integer(body.page, 1, 1, 10);
        const size = integer(body.size, 10, 1, 10);
        const search = typeof body.search === "string" ? body.search.slice(0, 200) : "";
        return jsonResponse(await service.list(userId, page, size, search));
      }
      case "get": {
        const postId = positiveInteger(body.postId);
        if (postId === null) return apiError(400, "文章编号格式不正确");
        const result = await service.get(userId, postId);
        return result ? jsonResponse(result) : apiError(404, "文章不存在");
      }
      case "create": {
        if (typeof body.idempotencyKey !== "string" || !/^[A-Za-z0-9._:-]{1,200}$/.test(body.idempotencyKey)
          || typeof body.title !== "string" || typeof body.content !== "string"
          || (body.summary !== undefined && body.summary !== null && typeof body.summary !== "string")
          || (body.categoryId !== undefined && body.categoryId !== null && typeof body.categoryId !== "string")
          || (body.tags !== undefined && body.tags !== null
            && (!Array.isArray(body.tags) || body.tags.some((tag) => typeof tag !== "string")))
          || typeof body.publish !== "boolean") {
          return apiError(400, "文章内容格式不正确");
        }
        return jsonResponse(await service.create(userId, {
          idempotencyKey: body.idempotencyKey, title: body.title, content: body.content,
          summary: body.summary as string | null | undefined, categoryId: body.categoryId as string | null | undefined,
          tags: body.tags as string[] | null | undefined, publish: body.publish,
        }));
      }
      case "update": {
        const postId = positiveInteger(body.postId);
        if (postId === null || !isObject(body.input)) return apiError(400, "文章更新参数格式不正确");
        return jsonResponse(await service.update(userId, postId, body.input));
      }
      case "delete": {
        const postId = positiveInteger(body.postId);
        if (postId === null) return apiError(400, "文章编号格式不正确");
        await service.delete(userId, postId);
        return jsonResponse({ deleted: true });
      }
      default:
        return apiError(400, "不支持的文章操作");
    }
  } catch (error) {
    if (error instanceof BlogManagementError || error instanceof BlogPostCreationError) {
      return apiError(error.status, error.message);
    }
    return apiError(503, "文章服务暂时不可用");
  }
}

async function readBody(request: Request): Promise<unknown> {
  const declared = request.headers.get("content-length");
  if (declared && (!/^\d+$/.test(declared) || Number(declared) > MAX_REQUEST_BYTES)) return apiError(413, "请求内容过大");
  const reader = request.body?.getReader();
  if (!reader) return null;
  const chunks: Uint8Array[] = [];
  let length = 0;
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      length += value.byteLength;
      if (length > MAX_REQUEST_BYTES) {
        await reader.cancel();
        return apiError(413, "请求内容过大");
      }
      chunks.push(value);
    }
  } finally { reader.releaseLock(); }
  const bytes = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  try { return JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes)) as unknown; }
  catch { return apiError(400, "请求内容格式不正确"); }
}

function isObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value) && !(value instanceof Response);
}
function positiveInteger(value: unknown): number | null {
  return typeof value === "number" && Number.isSafeInteger(value) && value > 0 ? value : null;
}
function integer(value: unknown, fallback: number, minimum: number, maximum: number): number {
  if (value === undefined || value === null) return fallback;
  return typeof value === "number" && Number.isSafeInteger(value) ? Math.min(maximum, Math.max(minimum, value)) : fallback;
}
