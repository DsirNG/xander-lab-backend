import { apiError, jsonResponse } from "@dinqor/worker-runtime";
import { AgentAttachmentError, AgentAttachmentService } from "../services/agent-attachment-service.js";

type UserResolver = () => Promise<number | Response>;
const MAX_MULTIPART_BYTES = 20 * 1024 * 1024 + 256 * 1024;

function success(data: unknown): Response {
  return jsonResponse({ code: 200, message: "success", data });
}

async function boundedBody(request: Request): Promise<Uint8Array | Response> {
  const contentLength = request.headers.get("content-length");
  if (contentLength && (!/^\d+$/.test(contentLength) || Number(contentLength) > MAX_MULTIPART_BYTES)) {
    return apiError(413, "单个附件不能超过20MB");
  }
  if (!request.body) return apiError(400, "附件不能为空");
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > MAX_MULTIPART_BYTES) {
        await reader.cancel();
        return apiError(413, "单个附件不能超过20MB");
      }
      chunks.push(value);
    }
  } catch {
    return apiError(400, "附件上传失败");
  } finally {
    reader.releaseLock();
  }
  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return bytes;
}

/** Handles one authenticated multipart attachment upload. */
export async function handleAgentAttachmentRequest(
  request: Request,
  service: AgentAttachmentService,
  resolveUser: UserResolver,
): Promise<Response | null> {
  const url = new URL(request.url);
  if (url.pathname !== "/api/agent/conversations/attachments" || request.method !== "POST") return null;
  const userId = await resolveUser();
  if (userId instanceof Response) return userId;
  const contentType = request.headers.get("content-type") ?? "";
  if (!contentType.toLowerCase().startsWith("multipart/form-data;")) return apiError(400, "附件请求格式不正确");
  const bytes = await boundedBody(request);
  if (bytes instanceof Response) return bytes;
  try {
    const multipartBuffer = new ArrayBuffer(bytes.byteLength);
    new Uint8Array(multipartBuffer).set(bytes);
    const form = await new Response(multipartBuffer, { headers: { "content-type": contentType } }).formData();
    const file = form.get("file");
    if (!(file instanceof File)) return apiError(400, "附件不能为空");
    const uploaded = await service.upload(userId, {
      name: file.name,
      type: file.type,
      size: file.size,
      bytes: new Uint8Array(await file.arrayBuffer()),
    });
    return success(uploaded);
  } catch (error) {
    if (error instanceof AgentAttachmentError) return apiError(error.status, error.message);
    if (error instanceof TypeError || error instanceof SyntaxError) return apiError(400, "附件请求格式不正确");
    return apiError(503, "附件存储暂不可用");
  }
}
