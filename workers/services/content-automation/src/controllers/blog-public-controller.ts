import { apiError, jsonResponse } from "@dinqor/worker-runtime";
import { ArticleContentTooLargeError } from "../interfaces/external-publication.js";
import { BlogPublicService } from "../services/blog-public-service.js";

const MAX_INTERNAL_BODY_BYTES = 8 * 1024;

function success(data: unknown): Response {
  return jsonResponse({ code: 200, message: "success", data });
}

function parseIntegerParameter(
  params: URLSearchParams,
  name: string,
  defaultValue: number,
): number | null {
  const raw = params.get(name);
  if (raw === null) return defaultValue;
  if (!/^-?\d+$/.test(raw)) return null;
  const value = Number(raw);
  return Number.isInteger(value) && value >= -2_147_483_648 && value <= 2_147_483_647
    ? value
    : null;
}

async function readInternalJson(request: Request): Promise<unknown> {
  const declaredLength = request.headers.get("content-length");
  if (declaredLength && (!/^\d+$/.test(declaredLength) || Number(declaredLength) > MAX_INTERNAL_BODY_BYTES)) {
    throw new Error("request-too-large");
  }
  const reader = request.body?.getReader();
  if (!reader) return null;
  const chunks: Uint8Array[] = [];
  let length = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      length += value.byteLength;
      if (length > MAX_INTERNAL_BODY_BYTES) {
        await reader.cancel();
        throw new Error("request-too-large");
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
  return JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes)) as unknown;
}

async function managedCount(request: Request, service: BlogPublicService): Promise<Response> {
  let body: unknown;
  try {
    body = await readInternalJson(request);
  } catch (error) {
    return apiError(error instanceof Error && error.message === "request-too-large" ? 413 : 400,
      error instanceof Error && error.message === "request-too-large" ? "Request body is too large" : "Invalid request body");
  }
  if (!body || typeof body !== "object" || Array.isArray(body)) return apiError(400, "Invalid request body");
  const userId = (body as Record<string, unknown>).userId;
  if (typeof userId !== "number" || !Number.isSafeInteger(userId) || userId < 1) {
    return apiError(400, "Invalid user ID");
  }
  return jsonResponse({ count: await service.countManagedPosts(userId) });
}

function errorResponse(error: unknown): Response {
  if (error instanceof ArticleContentTooLargeError) {
    return apiError(413, "Article content exceeds the Worker delivery limit");
  }
  return apiError(503, "Content service is temporarily unavailable");
}

export async function handleBlogPublicRequest(
  request: Request,
  service: BlogPublicService,
): Promise<Response | null> {
  const { pathname, searchParams } = new URL(request.url);
  if (request.method === "POST" && pathname === "/internal/content/profile/managed-count") {
    try {
      return await managedCount(request, service);
    } catch {
      return apiError(503, "Content service is temporarily unavailable");
    }
  }
  if (request.method === "POST") {
    const viewMatch = pathname.match(/^\/api\/blog\/posts\/(\d+)\/view$/);
    if (!viewMatch) return null;
    const postId = Number(viewMatch[1]);
    if (!Number.isSafeInteger(postId) || postId < 1) return apiError(400, "Invalid article ID");
    try {
      const rawIp = request.headers.get("x-dinqor-client-ip")?.trim();
      const rawUserAgent = request.headers.get("user-agent");
      return success(await service.recordPublicView({
        postId,
        ipAddress: rawIp && rawIp.length <= 45 ? rawIp : null,
        userAgent: rawUserAgent ? rawUserAgent.slice(0, 500) : null,
      }));
    } catch {
      return apiError(503, "Content service is temporarily unavailable");
    }
  }
  if (request.method !== "GET") return null;

  try {
    if (pathname === "/api/blog/posts") {
      const page = parseIntegerParameter(searchParams, "page", 1);
      const size = parseIntegerParameter(searchParams, "size", 10);
      if (page === null || size === null) return apiError(400, "Invalid page or size");
      return success(await service.listPublished({
        page,
        size,
        category: searchParams.get("category") ?? "",
        tag: searchParams.get("tag") ?? "",
        search: searchParams.get("search") ?? "",
      }));
    }
    if (pathname === "/api/blog/posts/recent") {
      const limit = parseIntegerParameter(searchParams, "limit", 5);
      if (limit === null) return apiError(400, "Invalid limit");
      return success(await service.recentPublished(limit));
    }
    const postIdMatch = pathname.match(/^\/api\/blog\/posts\/(\d+)$/);
    if (postIdMatch) {
      const postId = Number(postIdMatch[1]);
      if (!Number.isSafeInteger(postId) || postId < 1) return apiError(400, "Invalid article ID");
      const post = await service.findPublishedPost(postId);
      return post ? success(post) : apiError(404, "文章不存在");
    }
    if (pathname === "/api/blog/categories") return success(await service.listCategories());
    if (pathname === "/api/blog/tags") return success(await service.listTags());
    if (pathname === "/api/blog/tags/popular") {
      const limit = parseIntegerParameter(searchParams, "limit", 8);
      if (limit === null) return apiError(400, "Invalid limit");
      return success(await service.listTags(Math.min(Math.max(limit, 1), 50)));
    }
    return null;
  } catch (error) {
    return errorResponse(error);
  }
}
