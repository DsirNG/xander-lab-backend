import { apiError, jsonResponse } from "@dinqor/worker-runtime";
import type { IdentityProfileInput, IdentityProfileService } from "../services/identity-profile-service.js";
import type { IdentityUser } from "../interfaces/identity-repository.js";
import { userInfo } from "./identity-view.js";

const MAX_BODY_BYTES = 16 * 1024;
const LOGIN_EXPIRED_MESSAGE = "未登录或登录已过期";

function success(data: unknown): Response {
  return jsonResponse({ code: 200, message: "success", data });
}

async function readProfileRequest(request: Request): Promise<IdentityProfileInput | Response> {
  const contentLength = request.headers.get("content-length");
  if (contentLength && Number(contentLength) > MAX_BODY_BYTES) return apiError(413, "请求内容过大");
  if (!request.body) return apiError(400, "请求内容格式不正确");
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > MAX_BODY_BYTES) {
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
  try {
    const value: unknown = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
    return value && typeof value === "object" && !Array.isArray(value)
      ? value as IdentityProfileInput
      : apiError(400, "请求内容格式不正确");
  } catch {
    return apiError(400, "请求内容格式不正确");
  }
}

export async function handleIdentityProfileRequest(
  request: Request,
  profiles: IdentityProfileService,
  resolveUser: (request: Request) => Promise<IdentityUser | Response>,
): Promise<Response | null> {
  const url = new URL(request.url);
  if (request.method === "GET" && url.pathname === "/api/auth/me") {
    const user = await resolveUser(request);
    return user instanceof Response ? user : success(userInfo(user));
  }
  if (request.method === "PUT" && url.pathname === "/api/auth/profile") {
    const user = await resolveUser(request);
    if (user instanceof Response) return user;
    const input = await readProfileRequest(request);
    if (input instanceof Response) return input;
    const result = await profiles.update(user, input);
    if (result.kind === "invalid") return apiError(400, result.message);
    if (result.kind === "username_taken") return apiError(400, "该用户名已被使用");
    if (result.kind === "inactive") return apiError(401, LOGIN_EXPIRED_MESSAGE);
    return success(userInfo(result.user));
  }
  return null;
}
