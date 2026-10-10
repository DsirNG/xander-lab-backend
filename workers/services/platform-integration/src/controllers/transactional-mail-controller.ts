import { apiError, jsonResponse } from "@dinqor/worker-runtime";
import type { PlatformEnvironment } from "../interfaces/platform-environment.js";
import { TransactionalMailError, TransactionalMailService } from "../services/transactional-mail-service.js";

const MAX_BODY_BYTES = 64 * 1024;

export async function handleTransactionalMailRequest(
  request: Request,
  env: PlatformEnvironment,
  service: TransactionalMailService,
): Promise<Response | null> {
  const url = new URL(request.url);
  if (url.pathname !== "/internal/platform-integration/mail/reminder" || request.method !== "POST") return null;
  if (!env.INTERNAL_SERVICE_TOKEN || request.headers.get("x-dinqor-service-token") !== env.INTERNAL_SERVICE_TOKEN) {
    return apiError(404, "Not Found");
  }
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
  try {
    await service.send(body);
    return jsonResponse({ sent: true });
  } catch (error) {
    return error instanceof TransactionalMailError
      ? apiError(error.status, error.message) : apiError(503, "邮件投递暂时失败");
  }
}
