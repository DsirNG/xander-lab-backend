import { apiError, jsonResponse } from "@dinqor/worker-runtime";
import type { BlogPostCreationRequest } from "../services/blog-post-creation-service.js";
import { BlogPostCreationError, BlogPostCreationService } from "../services/blog-post-creation-service.js";

const MAX_REQUEST_BYTES = 4 * 1024 * 1024;

function object(value: unknown): Record<string, unknown> | null {
  return value && typeof value === "object" && !Array.isArray(value)
    ? value as Record<string, unknown> : null;
}

async function readJson(request: Request): Promise<unknown> {
  const declaredLength = request.headers.get("content-length");
  if (declaredLength && (!/^\d+$/.test(declaredLength) || Number(declaredLength) > MAX_REQUEST_BYTES)) {
    throw new BlogPostCreationError(413, "文章请求载荷过大");
  }
  const reader = request.body?.getReader();
  if (!reader) throw new BlogPostCreationError(400, "请求体不能为空");
  const chunks: Uint8Array[] = [];
  let length = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      length += value.byteLength;
      if (length > MAX_REQUEST_BYTES) {
        await reader.cancel();
        throw new BlogPostCreationError(413, "文章请求载荷过大");
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
    throw new BlogPostCreationError(400, "文章请求格式不正确");
  }
}

function userIdFromGateway(request: Request): number | null {
  const raw = request.headers.get("x-user-id")?.trim();
  if (!raw || !/^[1-9]\d{0,17}$/.test(raw)) return null;
  const userId = Number(raw);
  return Number.isSafeInteger(userId) ? userId : null;
}

export async function handleBlogPostCreationRequest(
  request: Request,
  service: BlogPostCreationService,
): Promise<Response | null> {
  if (new URL(request.url).pathname !== "/api/blog/posts" || request.method !== "POST") return null;
  const userId = userIdFromGateway(request);
  if (userId === null) return apiError(401, "未登录或登录已过期");

  try {
    const body = object(await readJson(request));
    if (!body) throw new BlogPostCreationError(400, "文章请求格式不正确");
    const input: BlogPostCreationRequest = {
      userId,
      idempotencyKey: request.headers.get("idempotency-key"),
      title: body.title as string,
      summary: body.summary as string | null | undefined,
      content: body.content as string,
      categoryId: body.categoryId as string,
      coverImage: body.coverImage as string | null | undefined,
      tags: body.tags as string[] | null | undefined,
      publish: body.publish as boolean | null | undefined,
    };
    return jsonResponse({ code: 200, message: "success", data: await service.create(input) });
  } catch (error) {
    if (error instanceof BlogPostCreationError) return apiError(error.status, error.message);
    return apiError(503, "文章保存暂时不可用");
  }
}
