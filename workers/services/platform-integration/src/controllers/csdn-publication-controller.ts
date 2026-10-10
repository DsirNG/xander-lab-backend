import { apiError, jsonResponse } from "@dinqor/worker-runtime";
import type { CsdnArticleInput } from "../interfaces/csdn-publication-port.js";
import { CsdnPublicationService } from "../services/csdn-publication-service.js";
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

async function readBody(request: Request): Promise<Record<string, unknown> | null> {
  const declaredLength = request.headers.get("content-length");
  if (declaredLength && (!/^\d+$/.test(declaredLength) || Number(declaredLength) > MAX_REQUEST_BYTES)) {
    throw new PlatformCredentialError(413, "站外同步文章载荷过大");
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
      if (length > MAX_REQUEST_BYTES) {
        await reader.cancel();
        throw new PlatformCredentialError(413, "站外同步文章载荷过大");
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
    return object(JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes)));
  } catch {
    throw new PlatformCredentialError(400, "站外同步请求格式不正确");
  }
}

export async function handleCsdnPublicationRequest(
  request: Request,
  service: CsdnPublicationService,
): Promise<Response | null> {
  const match = new URL(request.url).pathname.match(
    /^\/internal\/platform-integration\/blog-posts\/([1-9]\d{0,17})\/sync\/csdn$/,
  );
  if (!match || request.method !== "POST") return null;

  try {
    const postId = Number(match[1]);
    const body = await readBody(request);
    const post = object(body?.post);
    if (!Number.isSafeInteger(postId) || !body || !post
      || body.postId !== postId
      || typeof body.userId !== "number" || !Number.isSafeInteger(body.userId) || body.userId < 1
      || (post.id !== undefined && post.id !== null
        && (typeof post.id !== "number" || !Number.isSafeInteger(post.id) || post.id !== postId))
      || typeof post.title !== "string" || !post.title.trim() || post.title.length > 300
      || typeof post.content !== "string" || !post.content.trim()
      || !optionalString(post.summary, 2_000)
      || !optionalString(post.category, 200)
      || !optionalString(post.categoryName, 200)
      || !optionalString(post.coverImage, 2_000)
      || !optionalStringArray(post.tags)) {
      throw new PlatformCredentialError(400, "站外同步文章载荷非法");
    }

    const input: CsdnArticleInput = {
      userId: body.userId,
      postId,
      title: post.title,
      summary: typeof post.summary === "string" ? post.summary : "",
      content: post.content,
      category: typeof post.categoryName === "string" ? post.categoryName
        : typeof post.category === "string" ? post.category : null,
      coverImage: typeof post.coverImage === "string" ? post.coverImage : null,
      tags: Array.isArray(post.tags) ? post.tags as string[] : [],
    };
    return jsonResponse(await service.publish(input));
  } catch (error) {
    if (error instanceof PlatformCredentialError) return apiError(error.status, error.message);
    return apiError(503, "CSDN 发布服务暂不可用");
  }
}
