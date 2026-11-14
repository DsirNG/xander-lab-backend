import { apiError, jsonResponse } from "@dinqor/worker-runtime";
import type { PresentationPort } from "../interfaces/agent-presentation.js";
import { PresentationError } from "../services/agent-presentation-validation.js";
import { normalizePresentationId } from "../services/agent-presentation-id.js";

async function readInput(request: Request): Promise<Record<string, unknown>> {
  if (!request.body) throw new PresentationError(400, "PPT 请求格式无效");
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let length = 0;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      length += value.byteLength;
      if (length > 16_384) { await reader.cancel(); throw new PresentationError(413, "PPT 修改内容过大"); }
      chunks.push(value);
    }
  } finally { reader.releaseLock(); }
  const bytes = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
  const parsed: unknown = JSON.parse(new TextDecoder().decode(bytes));
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) throw new PresentationError(400, "PPT 参数无效");
  return parsed as Record<string, unknown>;
}

export async function handleAgentPresentationRequest(request: Request, service: PresentationPort | undefined,
  resolveUser: () => Promise<number | Response>): Promise<Response | null> {
  const url = new URL(request.url);
  const match = /^\/api\/agent\/presentations\/([^/]+)(?:\/(revise|retry|cancel))?$/.exec(url.pathname);
  if (!match) return null;
  const presentationId = normalizePresentationId(match[1]);
  if (!presentationId) return apiError(400, "PPT 标识无效");
  const userId = await resolveUser();
  if (userId instanceof Response) return userId;
  if (!service) return apiError(503, "PPT 渲染服务尚未配置");
  if ((match[2] && request.method !== "POST") || (!match[2] && request.method !== "GET")) return apiError(405, "Method not allowed");
  try {
    let input: Record<string, unknown> = {};
    if (request.method === "POST") {
      input = await readInput(request);
    } else if (url.searchParams.has("version")) input.version = Number(url.searchParams.get("version"));
    const data = await service.action(userId, presentationId, match[2] ?? "get", input);
    return jsonResponse({ code: 200, message: "success", data });
  } catch (error) {
    if (error instanceof PresentationError) return apiError(error.status, error.message);
    if (error instanceof SyntaxError) return apiError(400, "PPT 请求格式无效");
    return apiError(503, "PPT 服务暂不可用");
  }
}
