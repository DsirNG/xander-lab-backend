import { apiError, jsonResponse } from "@dinqor/worker-runtime";
import { BlogAgentModelError, BlogAgentModelService } from "../services/blog-agent-model-service.js";

const BASE_PATH = "/internal/agent/blog";
const MAX_BODY_BYTES = 512 * 1024;

async function readObject(request: Request): Promise<Record<string, unknown> | Response> {
  const declared = request.headers.get("content-length");
  if (declared && (!/^\d+$/.test(declared) || Number(declared) > MAX_BODY_BYTES)) {
    return apiError(413, "博客模型请求载荷过大");
  }
  if (!request.body) return apiError(400, "请求内容格式不正确");
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > MAX_BODY_BYTES) { await reader.cancel(); return apiError(413, "博客模型请求载荷过大"); }
      chunks.push(value);
    }
  } catch {
    return apiError(400, "请求内容格式不正确");
  } finally {
    reader.releaseLock();
  }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  try {
    const value: unknown = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
    return value && typeof value === "object" && !Array.isArray(value)
      ? value as Record<string, unknown> : apiError(400, "请求内容格式不正确");
  } catch {
    return apiError(400, "请求内容格式不正确");
  }
}

export async function handleBlogAgentModelRequest(
  request: Request,
  service: BlogAgentModelService,
): Promise<Response | null> {
  const url = new URL(request.url);
  if (!url.pathname.startsWith(`${BASE_PATH}/`)) return null;
  if (request.method !== "POST") return apiError(404, "Not Found");
  const body = await readObject(request);
  if (body instanceof Response) return body;
  try {
    const route = url.pathname.slice(BASE_PATH.length + 1);
    if (route === "topic-series") {
      if (!Number.isSafeInteger(body.userId) || (body.userId as number) < 1) {
        throw new BlogAgentModelError(400, "博客选题请求参数无效");
      }
      return jsonResponse(await service.generateTopics({
        seed: body.seed,
        count: body.count,
        audience: body.audience,
        tone: body.tone,
        excludeTitles: body.excludeTitles,
      }));
    }
    if (!route.startsWith("model/")) return apiError(404, "Not Found");
    const result = await service.execute(route.slice("model/".length), body);
    return jsonResponse(result);
  } catch (error) {
    return error instanceof BlogAgentModelError
      ? apiError(error.status, error.message)
      : apiError(503, "博客模型服务暂不可用");
  }
}
