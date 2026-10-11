import { apiError, jsonResponse } from "@dinqor/worker-runtime";
import { BlogMediaInputError, BlogMediaService, isPublicBlogImageObjectKey } from "../services/blog-media-service.js";

const MAX_IMAGE_BYTES = 10 * 1024 * 1024;
const MAX_INTERNAL_IMAGE_BYTES = 20 * 1024 * 1024;
const MAX_MULTIPART_BYTES = MAX_IMAGE_BYTES + 64 * 1024;
const MAX_INTERNAL_JSON_BYTES = 32 * 1024;
const USER_ID = /^[1-9]\d{0,17}$/;
const PUBLIC_MEDIA_PATH = "/api/public/blog/media/";

function success(data: unknown): Response {
  return jsonResponse({ code: 200, message: "success", data });
}

function errorResponse(error: unknown): Response {
  if (error instanceof BlogMediaInputError) return apiError(error.status, error.message);
  return apiError(503, "媒体服务暂不可用");
}

function publicImageUrl(request: Request, storedName: string, publicMediaBaseUrl?: string): string {
  const encodedPath = storedName.split("/").map(encodeURIComponent).join("/");
  const url = new URL(publicMediaBaseUrl || request.url);
  const basePath = publicMediaBaseUrl
    ? url.pathname.replace(/\/+$/, "")
    : PUBLIC_MEDIA_PATH.replace(/\/+$/, "");
  url.pathname = `${basePath}/${encodedPath}`;
  url.search = "";
  url.hash = "";
  return url.toString();
}

function publicMediaAsset<T extends { url: string; storedName: string | null }>(
  request: Request,
  asset: T,
  publicMediaBaseUrl?: string,
): T {
  return asset.storedName && isPublicBlogImageObjectKey(asset.storedName)
    ? { ...asset, url: publicImageUrl(request, asset.storedName, publicMediaBaseUrl) }
    : asset;
}

function publicAgentImage<T extends { url: string; storedName: string | null }>(
  request: Request,
  image: T,
  publicMediaBaseUrl?: string,
) {
  const { storedName, ...asset } = image;
  return storedName && isPublicBlogImageObjectKey(storedName)
    ? { ...asset, url: publicImageUrl(request, storedName, publicMediaBaseUrl) }
    : asset;
}

function parsePositive(value: unknown): number | null {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 1) return null;
  return value;
}

function projectedUserId(request: Request): number | null {
  const raw = request.headers.get("x-user-id")?.trim() ?? "";
  if (!USER_ID.test(raw)) return null;
  const id = Number(raw);
  return Number.isSafeInteger(id) ? id : null;
}

function queryInteger(raw: string | null, fallback: number): number | null {
  if (raw === null) return fallback;
  if (!/^-?\d+$/.test(raw)) return null;
  const parsed = Number(raw);
  return Number.isSafeInteger(parsed) && parsed >= -2_147_483_648 && parsed <= 2_147_483_647
    ? parsed : null;
}

async function readBytes(request: Request, limit: number): Promise<Uint8Array | null> {
  const declared = request.headers.get("content-length");
  if (declared && (!/^\d+$/.test(declared) || Number(declared) > limit)) return null;
  if (!request.body) return null;
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let length = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      length += value.byteLength;
      if (length > limit) {
        await reader.cancel();
        return null;
      }
      chunks.push(value);
    }
  } finally { reader.releaseLock(); }
  const result = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) { result.set(chunk, offset); offset += chunk.byteLength; }
  return result;
}

async function readJson(request: Request): Promise<Record<string, unknown> | null> {
  const bytes = await readBytes(request, MAX_INTERNAL_JSON_BYTES);
  if (!bytes) return null;
  try {
    const value: unknown = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
    return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : null;
  } catch { return null; }
}

async function formFile(request: Request): Promise<File | null> {
  const bytes = await readBytes(request, MAX_MULTIPART_BYTES);
  if (!bytes) return null;
  try {
    const body = new ArrayBuffer(bytes.byteLength);
    new Uint8Array(body).set(bytes);
    const copy = new Request(request.url, { method: "POST", headers: request.headers, body });
    const form = await copy.formData();
    const value = form.get("file");
    return value instanceof File ? value : null;
  } catch { return null; }
}

export async function handleBlogMediaRequest(
  request: Request,
  service: BlogMediaService,
  publicMediaBaseUrl?: string,
): Promise<Response | null> {
  const url = new URL(request.url);
  if (request.method === "GET" && url.pathname.startsWith(PUBLIC_MEDIA_PATH)) {
    let objectKey: string;
    try { objectKey = decodeURIComponent(url.pathname.slice(PUBLIC_MEDIA_PATH.length)); }
    catch { return apiError(404, "图片不存在"); }
    try {
      const image = await service.publicImage(objectKey);
      if (!image) return apiError(404, "图片不存在");
      return new Response(image.body, { headers: {
        "content-type": image.contentType,
        "content-length": String(image.size),
        "cache-control": "public, max-age=3600, s-maxage=86400",
        "x-content-type-options": "nosniff",
        "access-control-allow-origin": "*",
        "cross-origin-resource-policy": "cross-origin",
      } });
    } catch (error) { return errorResponse(error); }
  }
  if (request.method === "GET" && url.pathname === "/api/blog/media/images") {
    const userId = projectedUserId(request);
    if (userId === null) return apiError(401, "未登录或登录已过期");
    try {
      const images = await service.images(userId, url.searchParams.get("scope") ?? "recent", url.searchParams.get("keyword"));
      return success(images.map((image) => publicMediaAsset(request, image, publicMediaBaseUrl)));
    }
    catch (error) { return errorResponse(error); }
  }
  if (request.method === "GET" && url.pathname === "/api/blog/media/images/agent") {
    const userId = projectedUserId(request);
    if (userId === null) return apiError(401, "未登录或登录已过期");
    const page = queryInteger(url.searchParams.get("page"), 1);
    const size = queryInteger(url.searchParams.get("size"), 24);
    if (page === null || size === null) return apiError(400, "分页参数无效");
    try {
      const result = await service.agentImages(userId, page, size);
      return success({
        ...result,
        records: result.records.map((image) => publicAgentImage(request, image, publicMediaBaseUrl)),
      });
    }
    catch (error) { return errorResponse(error); }
  }
  if (request.method === "POST" && url.pathname === "/api/blog/media/images") {
    const userId = projectedUserId(request);
    if (userId === null) return apiError(401, "未登录或登录已过期");
    const file = await formFile(request);
    if (!file) return apiError(400, "请选择有效的图片文件");
    if (file.size > MAX_IMAGE_BYTES) return apiError(413, "图片大小不能超过 10MB");
    try {
      const bytes = new Uint8Array(await file.arrayBuffer());
      const image = await service.uploadImage({ userId, bytes, originalName: file.name, contentType: file.type });
      return success(publicMediaAsset(request, image, publicMediaBaseUrl));
    } catch (error) { return errorResponse(error); }
  }

  if (!url.pathname.startsWith("/internal/content/media/")) return null;
  if (request.method === "POST" && url.pathname === "/internal/content/media/upload-image") {
    const userId = Number(request.headers.get("x-dinqor-user-id"));
    const contentType = request.headers.get("x-dinqor-content-type") ?? "";
    let originalName = request.headers.get("x-dinqor-original-name");
    try { originalName = originalName ? decodeURIComponent(originalName) : null; }
    catch { return apiError(400, "图片名称格式无效"); }
    if (!Number.isSafeInteger(userId) || userId < 1 || !contentType) return apiError(400, "图片上传信息无效");
    const bytes = await readBytes(request, MAX_INTERNAL_IMAGE_BYTES);
    if (!bytes) return apiError(413, "图片内容为空或超过 20MB");
    try { return jsonResponse(await service.uploadImage({ userId, bytes, originalName, contentType, sourceType: "user_upload" })); }
    catch (error) { return errorResponse(error); }
  }
  if (request.method === "POST" && url.pathname === "/internal/content/media/delete-owned") {
    const body = await readJson(request);
    const userId = parsePositive(body?.userId);
    const id = parsePositive(body?.id);
    if (userId === null || id === null) return apiError(400, "媒体 ID 无效");
    try { return jsonResponse({ deleted: await service.deleteOwned(userId, id) }); }
    catch (error) { return errorResponse(error); }
  }
  if (request.method === "POST" && url.pathname === "/internal/content/media/record-stored") {
    const body = await readJson(request);
    const userId = parsePositive(body?.userId);
    const stored = body?.stored;
    if (userId === null || !stored || typeof stored !== "object" || Array.isArray(stored)) return apiError(400, "媒体信息无效");
    try {
      const asset = await service.recordStored(userId, stored as Parameters<BlogMediaService["recordStored"]>[1]);
      return jsonResponse(publicMediaAsset(request, asset, publicMediaBaseUrl));
    }
    catch (error) { return errorResponse(error); }
  }
  if (request.method === "POST" && url.pathname === "/internal/content/media/find-owned") {
    const body = await readJson(request);
    const userId = parsePositive(body?.userId);
    const id = parsePositive(body?.id);
    if (userId === null || id === null) return apiError(400, "媒体 ID 无效");
    try {
      const asset = await service.findOwned(userId, id);
      return jsonResponse(asset ? publicMediaAsset(request, asset, publicMediaBaseUrl) : null);
    }
    catch (error) { return errorResponse(error); }
  }
  if (request.method === "POST" && url.pathname === "/internal/content/media/list-by-task") {
    const body = await readJson(request);
    const userId = parsePositive(body?.userId);
    const taskId = parsePositive(body?.taskId);
    if (userId === null || taskId === null) return apiError(400, "Agent 任务 ID 无效");
    try {
      const assets = await service.listByTask(userId, taskId);
      return jsonResponse(assets.map((asset) => publicMediaAsset(request, asset, publicMediaBaseUrl)));
    }
    catch (error) { return errorResponse(error); }
  }
  if (request.method === "POST" && url.pathname === "/internal/content/media/find-reusable-generated") {
    const body = await readJson(request);
    const userId = parsePositive(body?.userId);
    const title = typeof body?.title === "string" ? body.title.trim().slice(0, 255) : "";
    if (userId === null || !title) return apiError(400, "图片标题无效");
    try {
      const asset = await service.findReusableGenerated(userId, title);
      return jsonResponse(asset ? publicMediaAsset(request, asset, publicMediaBaseUrl) : null);
    }
    catch (error) { return errorResponse(error); }
  }
  if (request.method === "POST" && url.pathname === "/internal/content/media/record-generated") {
    const userId = Number(request.headers.get("x-dinqor-user-id"));
    const taskValue = request.headers.get("x-dinqor-agent-task-id");
    const taskId = taskValue ? Number(taskValue) : null;
    const contentType = request.headers.get("x-dinqor-content-type") ?? "";
    let originalName = request.headers.get("x-dinqor-original-name");
    let generationMeta = request.headers.get("x-dinqor-generation-meta");
    try {
      originalName = originalName ? decodeURIComponent(originalName) : null;
      generationMeta = generationMeta ? decodeURIComponent(generationMeta) : null;
    } catch { return apiError(400, "图片生成元数据格式无效"); }
    if (!Number.isSafeInteger(userId) || userId < 1
      || taskId !== null && (!Number.isSafeInteger(taskId) || taskId < 1)
      || !contentType) return apiError(400, "图片生成信息无效");
    const bytes = await readBytes(request, MAX_IMAGE_BYTES);
    if (!bytes) return apiError(413, "图片内容为空或超过 10MB");
    try {
      const asset = await service.uploadImage({ userId, bytes, originalName, contentType,
        sourceType: "agent_generated", agentTaskId: taskId, generationMeta });
      return jsonResponse(publicMediaAsset(request, asset, publicMediaBaseUrl));
    } catch (error) { return errorResponse(error); }
  }
  return null;
}
