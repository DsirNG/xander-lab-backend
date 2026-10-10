import { apiError, jsonResponse } from "@dinqor/worker-runtime";
import type { IdentityUser } from "../interfaces/identity-repository.js";
import type { IdentityAccountService } from "../services/identity-account-service.js";
import type { VerificationCodeService } from "../services/verification-code-service.js";
import type { WechatAccountBindingResult, WechatAccountBindingService } from "../services/wechat-account-binding-service.js";
import type { WechatLoginService } from "../services/wechat-login-service.js";
import { readJsonObject } from "./http-input.js";

const MAX_BODY_BYTES = 16 * 1024;
const LOGIN_EXPIRED_MESSAGE = "未登录或登录已过期";
const EMAIL_PATTERN = /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/;

type RateLimitRule = { namespace: string; identifier: string; limit: number };

export interface WechatAuthEnvironment {
  AUTH_PASSWORD_PEPPER?: string;
  WECHAT_MINIAPP_APP_ID?: string;
  WECHAT_MINIAPP_APP_SECRET?: string;
  WECHAT_CREDENTIAL_ENCRYPTION_KEY?: string;
  WECHAT_MINIAPP_CODE2SESSION_URL?: string;
}

export interface WechatAuthControllerDependencies {
  environment: WechatAuthEnvironment;
  accounts: IdentityAccountService;
  wechatLogin: WechatLoginService;
  accountBinding: WechatAccountBindingService;
  verificationCodes: VerificationCodeService;
  enforceLimits(request: Request, rules: RateLimitRule[]): Promise<Response | null>;
  resolveUser(request: Request): Promise<IdentityUser | Response>;
  createTokenResponse(user: IdentityUser): Promise<Response>;
  grantRegistrationPoints(userId: number): Promise<void>;
}

function success(data: unknown): Response {
  return jsonResponse({ code: 200, message: "success", data });
}

function businessError(code: number, message: string): Response {
  return jsonResponse({ code, message, data: null });
}

function normalizedEmail(value: unknown): string | null {
  if (typeof value !== "string") return null;
  const email = value.trim().toLowerCase();
  return email.length <= 254 && EMAIL_PATTERN.test(email) ? email : null;
}

function requestIp(request: Request): string {
  return request.headers.get("x-dinqor-client-ip")?.trim().slice(0, 128) || "unknown";
}

function passwordPepper(env: WechatAuthEnvironment): string | null {
  const value = env.AUTH_PASSWORD_PEPPER;
  return typeof value === "string" && new TextEncoder().encode(value).byteLength >= 32 ? value : null;
}

function exchangeError(kind: string): Response {
  if (kind === "credential_invalid") {
    return apiError(503, "微信登录加密凭据不可用，请检查 WECHAT_CREDENTIAL_ENCRYPTION_KEY");
  }
  if (kind === "unconfigured") return apiError(503, "微信登录未配置，请联系管理员");
  if (kind === "invalid_code") return businessError(1001, "微信登录凭证无效");
  if (kind === "invalid_configuration") return apiError(503, "微信登录服务配置无效");
  return businessError(1001, "微信登录服务暂不可用，请稍后重试");
}

function bindingError(result: Exclude<WechatAccountBindingResult, { kind: "ready" }>): Response {
  switch (result.kind) {
    case "account_disabled":
      return apiError(400, "账号已被禁用，请联系管理员");
    case "wechat_email_conflict":
      return apiError(400, "该邮箱已绑定其他微信账号");
    case "wechat_bound_to_other_email":
      return apiError(409, "该微信账号已绑定其他邮箱");
    case "account_conflict":
      return apiError(409, "该微信账号和邮箱已分别绑定不同账户");
    case "migration_required":
      return apiError(409, "该微信账号已有独立账户，请先完成账户数据迁移后再绑定");
    case "password_service_unconfigured":
      return apiError(503, "身份服务尚未配置");
    case "session_expired":
      return apiError(401, LOGIN_EXPIRED_MESSAGE);
    case "binding_failed":
      return apiError(409, "账号绑定失败，请重试");
  }
}

async function wechatLogin(
  request: Request,
  dependencies: WechatAuthControllerDependencies,
): Promise<Response> {
  const limited = await dependencies.enforceLimits(request, [
    { namespace: "wechat-login-ip", identifier: requestIp(request), limit: 20 },
  ]);
  if (limited) return limited;
  const body = await readJsonObject(request, MAX_BODY_BYTES);
  if (body instanceof Response) return body;
  if (typeof body.code !== "string") return apiError(400, "微信登录凭证不能为空");

  const env = dependencies.environment;
  const exchanged = await dependencies.wechatLogin.exchangeCode(body.code, {
    fallbackAppId: env.WECHAT_MINIAPP_APP_ID,
    fallbackAppSecret: env.WECHAT_MINIAPP_APP_SECRET,
    encryptionKey: env.WECHAT_CREDENTIAL_ENCRYPTION_KEY,
    endpoint: env.WECHAT_MINIAPP_CODE2SESSION_URL,
  });
  if (exchanged.kind !== "success") return exchangeError(exchanged.kind);
  const user = await dependencies.accounts.findByOpenId(exchanged.openid);
  if (user) {
    if (user.status !== 1) return businessError(1001, "账号已被禁用，请联系管理员");
    await dependencies.grantRegistrationPoints(user.id);
    return dependencies.createTokenResponse(user);
  }

  const pendingToken = await dependencies.wechatLogin.createPendingBinding(exchanged.openid);
  return success({
    accessToken: null,
    refreshToken: null,
    tokenType: "Bearer",
    expiresIn: 0,
    userInfo: null,
    needsEmailBinding: true,
    pendingBind: true,
    pendingBindToken: pendingToken,
  });
}

async function bindWechat(
  request: Request,
  dependencies: WechatAuthControllerDependencies,
): Promise<Response> {
  const limited = await dependencies.enforceLimits(request, [
    { namespace: "wechat-bind-ip", identifier: requestIp(request), limit: 20 },
  ]);
  if (limited) return limited;
  const body = await readJsonObject(request, MAX_BODY_BYTES);
  if (body instanceof Response) return body;
  if (typeof body.pendingBindToken !== "string" || typeof body.code !== "string") {
    return apiError(400, "绑定凭证或验证码格式不正确");
  }
  const email = normalizedEmail(body.email);
  if (!email) return apiError(400, "邮箱格式不正确");
  const openid = await dependencies.wechatLogin.resolvePendingBinding(body.pendingBindToken);
  if (!openid) return apiError(400, "绑定凭证无效或已过期，请重新登录");
  if (!await dependencies.verificationCodes.verify(email, body.code.trim())) {
    return apiError(400, "验证码错误或已过期");
  }

  const result = await dependencies.accountBinding.bindPendingWechat(
    openid,
    email,
    passwordPepper(dependencies.environment),
  );
  if (result.kind !== "ready") return bindingError(result);
  await dependencies.wechatLogin.deletePendingBinding(body.pendingBindToken);
  await dependencies.grantRegistrationPoints(result.user.id);
  return dependencies.createTokenResponse(result.user);
}

async function skipWechatBinding(
  request: Request,
  dependencies: WechatAuthControllerDependencies,
): Promise<Response> {
  const limited = await dependencies.enforceLimits(request, [
    { namespace: "wechat-bind-ip", identifier: requestIp(request), limit: 20 },
  ]);
  if (limited) return limited;
  const body = await readJsonObject(request, MAX_BODY_BYTES);
  if (body instanceof Response) return body;
  if (typeof body.pendingBindToken !== "string") return apiError(400, "绑定凭证无效或已过期，请重新登录");
  const openid = await dependencies.wechatLogin.resolvePendingBinding(body.pendingBindToken);
  if (!openid) return apiError(400, "绑定凭证无效或已过期，请重新登录");

  const result = await dependencies.accountBinding.skipPendingWechat(
    openid,
    passwordPepper(dependencies.environment),
  );
  if (result.kind !== "ready") return bindingError(result);
  await dependencies.wechatLogin.deletePendingBinding(body.pendingBindToken);
  await dependencies.grantRegistrationPoints(result.user.id);
  return dependencies.createTokenResponse(result.user);
}

async function bindExistingWechatEmail(
  request: Request,
  dependencies: WechatAuthControllerDependencies,
): Promise<Response> {
  const user = await dependencies.resolveUser(request);
  if (user instanceof Response) return user;
  if (!user.openid) return apiError(400, "当前账号不是可绑定邮箱的微信账号");
  if (user.email) return apiError(400, "当前账号已绑定邮箱");
  const limited = await dependencies.enforceLimits(request, [
    { namespace: "wechat-bind-ip", identifier: requestIp(request), limit: 20 },
  ]);
  if (limited) return limited;
  const body = await readJsonObject(request, MAX_BODY_BYTES);
  if (body instanceof Response) return body;
  const email = normalizedEmail(body.email);
  if (!email || typeof body.code !== "string") return apiError(400, "邮箱或验证码格式不正确");
  if (!await dependencies.verificationCodes.verify(email, body.code.trim())) {
    return apiError(400, "验证码错误或已过期");
  }
  const result = await dependencies.accountBinding.bindEmailToExistingWechatUser(email, user);
  return result.kind === "ready"
    ? dependencies.createTokenResponse(result.user)
    : bindingError(result);
}

export async function handleWechatAuthRequest(
  request: Request,
  dependencies: WechatAuthControllerDependencies,
): Promise<Response | null> {
  const url = new URL(request.url);
  if (request.method !== "POST") return null;
  switch (url.pathname) {
    case "/api/auth/wechat-login":
      return wechatLogin(request, dependencies);
    case "/api/auth/wechat-bind":
      return bindWechat(request, dependencies);
    case "/api/auth/wechat-skip":
      return skipWechatBinding(request, dependencies);
    case "/api/auth/wechat-bind-existing":
      return bindExistingWechatEmail(request, dependencies);
    default:
      return null;
  }
}
