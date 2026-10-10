import { apiError, jsonResponse, type D1Binding } from "@dinqor/worker-runtime";
import type { IdentityAuthService } from "../services/identity-auth-service.js";
import { resolveCurrentUser, type IdentityAuthEnvironment } from "./identity-auth-controller.js";
import { UserAdminService } from "../services/user-admin-service.js";

export interface UserAdminEnvironment extends IdentityAuthEnvironment {
  DB: D1Binding;
}

function success(data: unknown): Response {
  return jsonResponse({ code: 200, message: "success", data });
}

async function readStatus(request: Request): Promise<0 | 1 | Response> {
  if (!request.body) return apiError(400, "请求内容格式不正确");
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > 16 * 1024) {
        await reader.cancel();
        return apiError(413, "请求内容过大");
      }
      chunks.push(value);
    }
  } catch {
    return apiError(400, "请求内容格式不正确");
  } finally {
    reader.releaseLock();
  }

  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  let body: unknown;
  try {
    body = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes)) as unknown;
  } catch {
    return apiError(400, "请求内容格式不正确");
  }
  if (!body || typeof body !== "object" || Array.isArray(body)) {
    return apiError(400, "请求内容格式不正确");
  }
  const status = (body as Record<string, unknown>).status;
  if (status !== 0 && status !== 1) return apiError(400, "状态只能是 0（封禁）或 1（解封）");
  return status;
}

export async function handleUserAdminRequest(
  request: Request,
  env: UserAdminEnvironment,
  auth: IdentityAuthService,
  service: UserAdminService,
): Promise<Response | null> {
  const url = new URL(request.url);
  const isList = request.method === "GET" && url.pathname === "/api/admin/users";
  const statusMatch = request.method === "PATCH"
    ? url.pathname.match(/^\/api\/admin\/users\/(\d+)\/status$/)
    : null;
  if (!isList && !statusMatch) return null;

  const operator = await resolveCurrentUser(request, env, auth);
  if (operator instanceof Response) return operator;
  if (operator.role !== "ADMIN") return apiError(403, "无权访问");

  if (isList) {
    const rawPage = Number(url.searchParams.get("page") ?? "1");
    const rawSize = Number(url.searchParams.get("size") ?? "10");
    const page = Number.isSafeInteger(rawPage) ? Math.max(1, rawPage) : 1;
    const size = Number.isSafeInteger(rawSize) ? Math.min(100, Math.max(1, rawSize)) : 10;
    if (!Number.isSafeInteger((page - 1) * size)) return apiError(400, "分页参数超出范围");
    const keyword = url.searchParams.get("keyword")?.trim().slice(0, 64) ?? "";
    return success(await service.list(page, size, keyword));
  }

  const targetId = Number(statusMatch?.[1]);
  if (!Number.isSafeInteger(targetId) || targetId < 1) return apiError(400, "用户 ID 格式不正确");
  const status = await readStatus(request);
  if (status instanceof Response) return status;
  const result = await service.changeStatus(operator, targetId, status);
  if (result.kind === "self") return apiError(400, "不能修改自己的账号状态");
  if (result.kind === "missing") return apiError(404, "用户不存在");
  if (result.kind === "admin") return apiError(403, "不能封禁管理员账号");
  return success(result.user);
}
