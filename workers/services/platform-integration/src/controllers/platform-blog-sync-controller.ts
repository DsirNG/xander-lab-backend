import { apiError, jsonResponse } from "@dinqor/worker-runtime";
import { PlatformBlogSyncError } from "../interfaces/platform-blog-sync.js";
import type { PlatformBlogSyncService } from "../services/platform-blog-sync-service.js";

const MAX_BODY_BYTES = 8 * 1024;

function success(data: unknown): Response {
  return jsonResponse({ code: 200, message: "success", data });
}

function parseUserId(value: string | null): number | null {
  if (!value || !/^[1-9]\d{0,17}$/.test(value)) return null;
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) ? parsed : null;
}

async function readJuejinOptions(request: Request): Promise<{ categoryId?: string | null; tagIds?: string[] | null } | Response> {
  const declared = request.headers.get("content-length");
  if (declared && (!/^\d+$/.test(declared) || Number(declared) > MAX_BODY_BYTES)) {
    return apiError(413, "请求内容过大");
  }
  const reader = request.body?.getReader();
  if (!reader) return {};
  const chunks: Uint8Array[] = [];
  let length = 0;
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      length += value.byteLength;
      if (length > MAX_BODY_BYTES) {
        await reader.cancel();
        return apiError(413, "请求内容过大");
      }
      chunks.push(value);
    }
  } finally { reader.releaseLock(); }
  if (length === 0) return {};
  const bytes = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  let value: unknown;
  try { value = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes)) as unknown; }
  catch { return apiError(400, "请求内容格式不正确"); }
  if (!isObject(value)) return apiError(400, "请求内容格式不正确");
  const categoryId = value.juejinCategoryId;
  const tagIds = value.juejinTagIds;
  if (categoryId !== undefined && categoryId !== null
    && (typeof categoryId !== "string" || categoryId.length > 200)) return apiError(400, "掘金分类参数非法");
  if (tagIds !== undefined && tagIds !== null
    && (!Array.isArray(tagIds) || tagIds.length > 50
      || tagIds.some((tag) => typeof tag !== "string" || tag.length > 200))) return apiError(400, "掘金标签参数非法");
  return {
    categoryId: categoryId as string | null | undefined,
    tagIds: tagIds as string[] | null | undefined,
  };
}

function failure(error: unknown): Response {
  if (error instanceof PlatformBlogSyncError) return apiError(error.status, error.message);
  return apiError(503, "站外同步暂时不可用");
}

export async function handlePlatformBlogSyncRequest(
  request: Request,
  service: PlatformBlogSyncService,
): Promise<Response | null> {
  const match = new URL(request.url).pathname.match(/^\/api\/blog\/posts\/([1-9]\d{0,17})\/sync\/(csdn|juejin)$/);
  if (!match) return null;
  if (request.method !== "POST") return null;
  const userId = parseUserId(request.headers.get("x-user-id"));
  if (!userId) return apiError(401, "未登录或登录已过期");
  const postId = Number(match[1]);
  if (!Number.isSafeInteger(postId)) return apiError(400, "文章 ID 无效");
  const platform = match[2] as "csdn" | "juejin";
  try {
    const options = platform === "juejin" ? await readJuejinOptions(request) : {};
    if (options instanceof Response) return options;
    return success(await service.sync({ userId, postId, platform, ...options }));
  } catch (error) {
    return failure(error);
  }
}

function isObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}
