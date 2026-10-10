import { apiError, jsonResponse } from "@dinqor/worker-runtime";
import type { ExternalPublicationPlatform } from "../interfaces/external-publication.js";
import { ExternalPublicationError, ExternalPublicationService } from "../services/external-publication-service.js";

const MAX_REQUEST_BYTES = 8 * 1024;

async function readJson(request: Request): Promise<unknown> {
  const declaredLength = request.headers.get("content-length");
  if (declaredLength && (!/^\d+$/.test(declaredLength) || Number(declaredLength) > MAX_REQUEST_BYTES)) {
    throw new ExternalPublicationError(413, "站外同步请求载荷过大", "REQUEST_TOO_LARGE");
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
        throw new ExternalPublicationError(413, "站外同步请求载荷过大", "REQUEST_TOO_LARGE");
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
    throw new ExternalPublicationError(400, "站外同步请求格式不正确", "INVALID_REQUEST_BODY");
  }
}

function requestIdentity(value: unknown, postId: number): number {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new ExternalPublicationError(400, "站外同步请求参数非法", "INVALID_PUBLICATION_REQUEST");
  }
  const body = value as Record<string, unknown>;
  if (!Number.isSafeInteger(body.userId) || typeof body.userId !== "number" || body.userId < 1
    || body.postId !== postId) {
    throw new ExternalPublicationError(400, "站外同步请求参数非法", "INVALID_PUBLICATION_REQUEST");
  }
  return body.userId;
}

function errorResponse(error: unknown): Response {
  if (error instanceof ExternalPublicationError) return apiError(error.status, error.message, { code: error.code });
  return apiError(503, "Content service is temporarily unavailable");
}

export async function handleExternalPublicationRequest(
  request: Request,
  service: ExternalPublicationService,
): Promise<Response | null> {
  const match = new URL(request.url).pathname.match(
    /^\/internal\/agent\/publications\/([1-9]\d{0,17})\/(sync|find-succeeded)\/(csdn|juejin)$/,
  );
  if (!match || request.method !== "POST") return null;

  try {
    const postId = Number(match[1]);
    if (!Number.isSafeInteger(postId)) {
      throw new ExternalPublicationError(400, "站外同步请求参数非法", "INVALID_PUBLICATION_REQUEST");
    }
    const requestBody = await readJson(request);
    const userId = requestIdentity(requestBody, postId);
    const platform = match[3] as ExternalPublicationPlatform;
    if (match[2] === "find-succeeded") {
      const result = await service.findSucceeded(userId, postId, platform);
      return result ? jsonResponse(result) : jsonResponse(null);
    }
    const body = requestBody as Record<string, unknown>;
    const categoryId = body.juejinCategoryId;
    const tagIds = body.juejinTagIds;
    if ((categoryId !== undefined && categoryId !== null
        && (typeof categoryId !== "string" || categoryId.length > 200))
      || (tagIds !== undefined && tagIds !== null
        && (!Array.isArray(tagIds) || tagIds.length > 50
          || tagIds.some((value) => typeof value !== "string" || value.length > 200)))) {
      throw new ExternalPublicationError(400, "站外同步请求参数非法", "INVALID_PUBLICATION_REQUEST");
    }
    return jsonResponse(await service.sync(userId, postId, platform, {
      categoryId: categoryId as string | null | undefined,
      tagIds: tagIds as string[] | null | undefined,
    }));
  } catch (error) {
    return errorResponse(error);
  }
}
