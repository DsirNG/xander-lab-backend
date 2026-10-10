import { apiError, jsonResponse } from "@dinqor/worker-runtime";
import type { JuejinPublicationRequest } from "../services/juejin-publication-service.js";
import { JuejinPublicationService } from "../services/juejin-publication-service.js";
import { PlatformCredentialError } from "../services/platform-credential-service.js";

const MAX_REQUEST_BYTES = 4 * 1024 * 1024;

function object(value: unknown): Record<string, unknown> | null {
  return value && typeof value === "object" && !Array.isArray(value)
    ? value as Record<string, unknown> : null;
}

function optionalString(value: unknown, maxLength: number): boolean {
  return value === undefined || value === null || (typeof value === "string" && value.length <= maxLength);
}

function optionalStringArray(value: unknown): boolean {
  return value === undefined || value === null || (Array.isArray(value)
    && value.length <= 50 && value.every((tag) => typeof tag === "string" && tag.length <= 200));
}

function mapError(error: unknown): Response {
  return error instanceof PlatformCredentialError
    ? apiError(error.status, error.message)
    : apiError(503, "掘金发布服务暂不可用");
}

export async function handleJuejinPublicationRequest(
  request: Request,
  service: JuejinPublicationService,
): Promise<Response | null> {
  const match = new URL(request.url).pathname.match(
    /^\/internal\/platform-integration\/blog-posts\/([1-9]\d{0,17})\/sync\/juejin$/,
  );
  if (!match || request.method !== "POST") return null;

  const postId = Number(match[1]);
  if (!Number.isSafeInteger(postId)) return apiError(400, "站外同步请求参数非法");
  const declaredLength = request.headers.get("content-length");
  if (declaredLength && (!/^\d+$/.test(declaredLength) || Number(declaredLength) > MAX_REQUEST_BYTES)) {
    return apiError(413, "站外同步文章载荷过大");
  }

  let body: Record<string, unknown> | null;
  try {
    const reader = request.body?.getReader();
    const chunks: Uint8Array[] = [];
    let length = 0;
    if (reader) {
      try {
        while (true) {
          const { done, value } = await reader.read();
          if (done) break;
          length += value.byteLength;
          if (length > MAX_REQUEST_BYTES) {
            await reader.cancel();
            return apiError(413, "站外同步文章载荷过大");
          }
          chunks.push(value);
        }
      } finally {
        reader.releaseLock();
      }
    }
    const bytes = new Uint8Array(length);
    let offset = 0;
    for (const chunk of chunks) {
      bytes.set(chunk, offset);
      offset += chunk.byteLength;
    }
    const raw = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
    body = object(JSON.parse(raw));
  } catch {
    return apiError(400, "站外同步请求格式不正确");
  }
  const post = object(body?.post);
  if (!body || !post || body.postId !== postId
    || typeof body.userId !== "number" || !Number.isSafeInteger(body.userId) || body.userId < 1
    || (post.id !== undefined && post.id !== null
      && (typeof post.id !== "number" || !Number.isSafeInteger(post.id) || post.id !== postId))
    || typeof post.title !== "string" || typeof post.content !== "string"
    || !optionalString(post.summary, 2_000)
    || !optionalString(post.category, 200)
    || !optionalString(post.categoryName, 200)
    || !optionalString(post.coverImage, 2_000)
    || !optionalStringArray(post.tags)
    || (body.juejinCategoryId !== undefined && body.juejinCategoryId !== null
      && (typeof body.juejinCategoryId !== "string" || body.juejinCategoryId.length > 200))
    || (body.juejinTagIds !== undefined && body.juejinTagIds !== null
      && !optionalStringArray(body.juejinTagIds))) {
    return apiError(400, "站外同步文章载荷非法");
  }

  const publication: JuejinPublicationRequest = {
    userId: body.userId,
    postId,
    categoryId: body.juejinCategoryId as string | null | undefined,
    tagIds: body.juejinTagIds as string[] | null | undefined,
    post: {
      id: post.id as number | null | undefined,
      title: post.title,
      summary: post.summary as string | null | undefined,
      content: post.content,
      category: post.category as string | null | undefined,
      categoryName: post.categoryName as string | null | undefined,
      coverImage: post.coverImage as string | null | undefined,
      tags: post.tags as string[] | null | undefined,
    },
  };
  try {
    return jsonResponse(await service.publish(publication));
  } catch (error) {
    return mapError(error);
  }
}
