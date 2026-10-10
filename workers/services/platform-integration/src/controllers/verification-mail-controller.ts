import { apiError, jsonResponse } from "@dinqor/worker-runtime";
import { VerificationMailError, type VerificationMailService } from "../services/verification-mail-service.js";

const MAX_BODY_BYTES = 2 * 1024;

export async function handleVerificationMailRequest(
  request: Request,
  service: VerificationMailService,
): Promise<Response | null> {
  const url = new URL(request.url);
  if (url.pathname !== "/internal/mail/verification-code" || request.method !== "POST") return null;
  const length = request.headers.get("content-length");
  if (length && (!/^\d+$/.test(length) || Number(length) > MAX_BODY_BYTES)) return apiError(413, "请求内容过大");
  let body: unknown;
  try {
    const raw = await request.text();
    if (new TextEncoder().encode(raw).byteLength > MAX_BODY_BYTES) return apiError(413, "请求内容过大");
    body = JSON.parse(raw);
  } catch {
    return apiError(400, "请求内容格式不正确");
  }
  if (!body || typeof body !== "object" || Array.isArray(body)) return apiError(400, "请求内容格式不正确");
  const data = body as Record<string, unknown>;
  try {
    await service.send(data.email, data.code);
    return jsonResponse({ sent: true });
  } catch (error) {
    return error instanceof VerificationMailError
      ? apiError(error.status, error.message)
      : apiError(503, "邮件投递暂时失败");
  }
}
