import { apiError, jsonResponse } from "@dinqor/worker-runtime";
import { EmailReminderError, EmailReminderService } from "../services/email-reminder-service.js";

const MAX_BODY_BYTES = 64 * 1024;

function success(data: unknown): Response {
  return jsonResponse({ code: 200, message: "success", data });
}

function userId(request: Request): number | null {
  const raw = request.headers.get("x-user-id")?.trim();
  if (!raw || !/^[1-9]\d{0,17}$/.test(raw)) return null;
  const parsed = Number(raw);
  return Number.isSafeInteger(parsed) ? parsed : null;
}

async function readJson(request: Request): Promise<unknown> {
  const length = request.headers.get("content-length");
  if (length && (!/^\d+$/.test(length) || Number(length) > MAX_BODY_BYTES)) {
    throw new EmailReminderError(413, "请求内容过大");
  }
  const raw = await request.text();
  if (new TextEncoder().encode(raw).byteLength > MAX_BODY_BYTES) throw new EmailReminderError(413, "请求内容过大");
  try { return JSON.parse(raw) as unknown; }
  catch { throw new EmailReminderError(400, "请求内容格式不正确"); }
}

function errorResponse(error: unknown): Response {
  return error instanceof EmailReminderError
    ? apiError(error.status, error.message)
    : apiError(503, "定时邮件服务暂不可用");
}

export async function handleEmailReminderRequest(
  request: Request,
  service: EmailReminderService,
): Promise<Response | null> {
  const url = new URL(request.url);
  if (url.pathname === "/api/email-reminders" && request.method === "GET") {
    try { return success(await service.list(userId(request), url.searchParams)); }
    catch (error) { return errorResponse(error); }
  }
  if (url.pathname === "/api/email-reminders" && request.method === "POST") {
    try { return success(await service.create(userId(request), await readJson(request))); }
    catch (error) { return errorResponse(error); }
  }
  const statusMatch = url.pathname.match(/^\/api\/email-reminders\/([1-9]\d*)\/status$/);
  const deleteMatch = url.pathname.match(/^\/api\/email-reminders\/([1-9]\d*)$/);
  const match = (request.method === "PATCH" && statusMatch) || (request.method === "DELETE" && deleteMatch);
  if (!match) return null;
  const rawId = (statusMatch ?? deleteMatch)![1];
  const taskId = Number(rawId);
  if (!Number.isSafeInteger(taskId)) return apiError(400, "Invalid reminder ID");
  try {
    if (statusMatch && request.method === "PATCH") {
      const body = await readJson(request);
      const status = body && typeof body === "object" && !Array.isArray(body)
        ? (body as Record<string, unknown>).status : null;
      return success(await service.updateStatus(userId(request), taskId, status));
    }
    await service.delete(userId(request), taskId);
    return success(null);
  } catch (error) {
    return errorResponse(error);
  }
}
