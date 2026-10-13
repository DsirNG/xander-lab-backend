import { apiError, jsonResponse } from "@dinqor/worker-runtime";
import type { PlatformEnvironment } from "../interfaces/platform-environment.js";
import { PlatformCredentialError, type PlatformCredentialService } from "../services/platform-credential-service.js";
import { PlatformAuthorizationError, type PlatformAuthorizationService } from "../services/platform-authorization-service.js";
import type { PlatformExtensionAuthorizationService } from "../services/platform-extension-authorization-service.js";
import { CONNECTABLE_PLATFORMS, type ConnectablePlatform } from "../interfaces/platform-connection.js";

const MAX_CREDENTIAL_BYTES = 32 * 1024;
const CONNECTABLE_PLATFORM_PATTERN = CONNECTABLE_PLATFORMS.join("|");

function success(data: unknown): Response {
  const response = jsonResponse({ code: 200, message: "success", data });
  response.headers.set("cache-control", "no-store");
  return response;
}

function userId(value: string | null): number | null {
  if (!value || !/^[1-9]\d{0,17}$/.test(value)) return null;
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) ? parsed : null;
}

async function objectBody(request: Request): Promise<Record<string, unknown> | Response> {
  const length = request.headers.get("content-length");
  if (length && (!/^\d+$/.test(length) || Number(length) > MAX_CREDENTIAL_BYTES)) {
    return apiError(413, "请求内容过大");
  }
  try {
    const raw = await request.text();
    if (new TextEncoder().encode(raw).byteLength > MAX_CREDENTIAL_BYTES) return apiError(413, "请求内容过大");
    const value: unknown = JSON.parse(raw);
    if (!value || typeof value !== "object" || Array.isArray(value)) return apiError(400, "请求内容格式不正确");
    return value as Record<string, unknown>;
  } catch {
    return apiError(400, "请求内容格式不正确");
  }
}

function mapError(error: unknown): Response {
  return error instanceof PlatformCredentialError || error instanceof PlatformAuthorizationError
    ? apiError(error.status, error.message)
    : apiError(503, "平台授权服务暂不可用");
}

export async function handlePlatformCredentialRequest(
  request: Request,
  _env: PlatformEnvironment,
  service: PlatformCredentialService,
  authorization: PlatformAuthorizationService,
  extensionAuthorization: PlatformExtensionAuthorizationService,
): Promise<Response | null> {
  const path = new URL(request.url).pathname;
  if (path === "/api/platform-connections" && request.method === "GET") {
    const owner = userId(request.headers.get("x-user-id"));
    if (!owner) return apiError(401, "未登录或登录已过期");
    try {
      return success(await service.statuses(owner));
    } catch (error) {
      return mapError(error);
    }
  }

  const publicStatus = path.match(new RegExp(`^/api/publishing/(${CONNECTABLE_PLATFORM_PATTERN})/authorization/status$`));
  if (publicStatus && request.method === "GET") {
    const owner = userId(request.headers.get("x-user-id"));
    if (!owner) return apiError(401, "未登录或登录已过期");
    try {
      return success(await service.status(owner, publicStatus[1] as ConnectablePlatform));
    } catch (error) {
      return mapError(error);
    }
  }

  const publicStart = path.match(new RegExp(`^/api/publishing/(${CONNECTABLE_PLATFORM_PATTERN})/authorization/start$`));
  if (publicStart && request.method === "POST") {
    const owner = userId(request.headers.get("x-user-id"));
    if (!owner) return apiError(401, "未登录或登录已过期");
    try {
      return success(await extensionAuthorization.start(owner, publicStart[1] as ConnectablePlatform));
    } catch (error) {
      return mapError(error);
    }
  }

  const extensionCancel = path.match(new RegExp(`^/api/publishing/(${CONNECTABLE_PLATFORM_PATTERN})/authorization/extension/([0-9a-f-]{36})/cancel$`, "i"));
  if (extensionCancel && request.method === "POST") {
    const owner = userId(request.headers.get("x-user-id"));
    if (!owner) return apiError(401, "未登录或登录已过期");
    try {
      await extensionAuthorization.cancel(owner, extensionCancel[2]);
      return success(null);
    } catch (error) {
      return mapError(error);
    }
  }

  const extensionComplete = path.match(new RegExp(`^/api/publishing/(${CONNECTABLE_PLATFORM_PATTERN})/authorization/extension/([0-9a-f-]{36})/complete$`, "i"));
  if (extensionComplete && request.method === "POST") {
    const token = request.headers.get("authorization")?.match(/^Bearer ([A-Za-z0-9_-]{43})$/)?.[1];
    if (!token) return apiError(401, "授权任务令牌无效或已过期");
    const body = await objectBody(request);
    if (body instanceof Response) return body;
    if (body.accountName !== undefined && body.accountName !== null && typeof body.accountName !== "string") {
      return apiError(400, "平台账号名称格式不正确");
    }
    const accountName = typeof body.accountName === "string" ? body.accountName : null;
    if (body.accountProfile !== undefined && body.accountProfile !== null
      && (!body.accountProfile || typeof body.accountProfile !== "object" || Array.isArray(body.accountProfile))) {
      return apiError(400, "平台账号资料格式不正确");
    }
    const accountProfile = body.accountProfile as { nickname?: unknown; avatarUrl?: unknown } | null | undefined;
    if (accountProfile && ((accountProfile.nickname !== undefined && accountProfile.nickname !== null && typeof accountProfile.nickname !== "string")
      || (accountProfile.avatarUrl !== undefined && accountProfile.avatarUrl !== null && typeof accountProfile.avatarUrl !== "string"))) {
      return apiError(400, "平台账号资料格式不正确");
    }
    try {
      await extensionAuthorization.complete(
        extensionComplete[2], extensionComplete[1] as ConnectablePlatform, token, body.credential, accountName,
        accountProfile ? { nickname: typeof accountProfile.nickname === "string" ? accountProfile.nickname : null,
          avatarUrl: typeof accountProfile.avatarUrl === "string" ? accountProfile.avatarUrl : null } : null,
      );
      return success({ authorized: true });
    } catch (error) {
      return mapError(error);
    }
  }

  const publicCancel = path.match(/^\/api\/publishing\/(csdn|juejin)\/authorization\/cancel$/);
  if (publicCancel && request.method === "POST") {
    const owner = userId(request.headers.get("x-user-id"));
    if (!owner) return apiError(401, "未登录或登录已过期");
    try {
      await authorization.cancel(owner, publicCancel[1] as "csdn" | "juejin");
      return success(null);
    } catch (error) {
      return mapError(error);
    }
  }

  const publicDisconnect = path.match(new RegExp(`^/api/publishing/(${CONNECTABLE_PLATFORM_PATTERN})/authorization$`));
  if (publicDisconnect && request.method === "DELETE") {
    const owner = userId(request.headers.get("x-user-id"));
    if (!owner) return apiError(401, "未登录或登录已过期");
    try {
      const platform = publicDisconnect[1] as ConnectablePlatform;
      await extensionAuthorization.cancelForPlatform(owner, platform);
      if (platform === "csdn" || platform === "juejin") await authorization.disconnect(owner, platform);
      else await service.delete(owner, platform);
      return success(null);
    } catch (error) {
      return mapError(error);
    }
  }

  const internalStatus = path.match(/^\/internal\/platform-integration\/platform-credentials\/([1-9]\d{0,17})\/(csdn|juejin)\/status$/);
  if (internalStatus && request.method === "GET") {
    const owner = Number(internalStatus[1]);
    if (!Number.isSafeInteger(owner)) return apiError(400, "凭证属主参数非法");
    try {
      return jsonResponse({ authorized: await service.isAuthorized(owner, internalStatus[2] as "csdn" | "juejin") });
    } catch {
      return jsonResponse({ authorized: false }, 503);
    }
  }

  const internalSave = path.match(/^\/internal\/v1\/platform-credentials\/([1-9]\d{0,17})\/(csdn|juejin)$/);
  if (internalSave && request.method === "PUT") {
    const owner = Number(internalSave[1]);
    if (!Number.isSafeInteger(owner)) return apiError(400, "凭证属主参数非法");
    const body = await objectBody(request);
    if (body instanceof Response) return body;
    if (body.accountName !== undefined && body.accountName !== null && typeof body.accountName !== "string") {
      return apiError(400, "平台账号名称格式不正确");
    }
    const accountName = body.accountName === undefined || body.accountName === null
      ? null : body.accountName as string;
    try {
      await service.save(owner, internalSave[2] as "csdn" | "juejin", body.credential, accountName);
      return success({ saved: true });
    } catch (error) {
      return mapError(error);
    }
  }

  const internalDisconnect = path.match(/^\/internal\/platform-integration\/platform-credentials\/([1-9]\d{0,17})\/(csdn|juejin)\/authorization$/);
  if (internalDisconnect && request.method === "DELETE") {
    const owner = Number(internalDisconnect[1]);
    if (!Number.isSafeInteger(owner)) return apiError(400, "凭证属主参数非法");
    try {
      await authorization.disconnect(owner, internalDisconnect[2] as "csdn" | "juejin");
      return jsonResponse({ deleted: true });
    } catch (error) {
      return mapError(error);
    }
  }

  const internalAuthorization = path.match(/^\/internal\/platform-integration\/platform-credentials\/([1-9]\d{0,17})\/(csdn|juejin)\/authorization\/(start|cancel)$/);
  if (internalAuthorization && request.method === "POST") {
    const owner = Number(internalAuthorization[1]);
    if (!Number.isSafeInteger(owner)) return apiError(400, "凭证属主参数非法");
    const platform = internalAuthorization[2] as "csdn" | "juejin";
    try {
      if (internalAuthorization[3] === "start") return jsonResponse(await authorization.start(owner, platform));
      await authorization.cancel(owner, platform);
      return jsonResponse({ cancelled: true });
    } catch (error) {
      return mapError(error);
    }
  }
  return null;
}
