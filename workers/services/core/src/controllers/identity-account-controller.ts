import { apiError, jsonResponse } from "@dinqor/worker-runtime";
import type { IdentityUser } from "../interfaces/identity-repository.js";
import type { IdentityAccountService } from "../services/identity-account-service.js";
import type { VerificationCodeService } from "../services/verification-code-service.js";
import { readJsonObject } from "./http-input.js";

const MAX_BODY_BYTES = 16 * 1024;
const EMAIL_PATTERN = /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/;

interface LoginInput {
  type?: unknown;
  account?: unknown;
  password?: unknown;
  code?: unknown;
}

type RateLimitRule = { namespace: string; identifier: string; limit: number };

export interface IdentityAccountControllerDependencies {
  accounts: IdentityAccountService;
  verificationCodes: VerificationCodeService;
  passwordPepper?: string;
  enforceLimits(request: Request, rules: RateLimitRule[]): Promise<Response | null>;
  resolveUser(request: Request): Promise<IdentityUser | Response>;
  createTokenResponse(user: IdentityUser): Promise<Response>;
  grantRegistrationPoints(userId: number): Promise<void>;
}

function success(data: unknown): Response {
  return jsonResponse({ code: 200, message: "success", data });
}

function authBusinessError(code: number, message: string): Response {
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

function configuredPepper(value?: string): string | null {
  return typeof value === "string" && new TextEncoder().encode(value).byteLength >= 32
    ? value
    : null;
}

async function sendVerificationCode(
  request: Request,
  email: string,
  dependencies: IdentityAccountControllerDependencies,
): Promise<Response> {
  const limited = await dependencies.enforceLimits(request, [
    { namespace: "auth-code-email", identifier: email, limit: 1 },
    { namespace: "auth-code-ip", identifier: requestIp(request), limit: 20 },
  ]);
  if (limited) return limited;
  const result = await dependencies.verificationCodes.send(email);
  if (result === "sent") return success(null);
  if (result === "unconfigured") return apiError(503, "邮件服务尚未配置");
  return apiError(503, "验证码发送失败，请稍后重试");
}

async function login(
  request: Request,
  dependencies: IdentityAccountControllerDependencies,
): Promise<Response> {
  const pepper = configuredPepper(dependencies.passwordPepper);
  if (!pepper) return apiError(503, "身份服务尚未配置");
  const body = await readJsonObject(request, MAX_BODY_BYTES);
  if (body instanceof Response) return body;
  const input = body as LoginInput;
  const type = input.type === undefined ? "password" : input.type;
  if (type !== "password" && type !== "code") return apiError(400, "登录类型不正确");
  if (typeof input.account !== "string" || !input.account.trim() || input.account.length > 254) {
    return apiError(400, "账号不能为空或过长");
  }
  const account = input.account.trim();
  const limited = await dependencies.enforceLimits(request, [
    { namespace: "auth-login-account", identifier: account.toLowerCase(), limit: 5 },
    { namespace: "auth-login-ip", identifier: requestIp(request), limit: 20 },
  ]);
  if (limited) return limited;

  let user: IdentityUser | null;
  if (type === "code") {
    const email = normalizedEmail(account);
    if (!email || typeof input.code !== "string" || !/^\d{6}$/.test(input.code.trim())) {
      return authBusinessError(1001, "验证码错误或已过期");
    }
    if (!await dependencies.verificationCodes.verify(email, input.code.trim())) {
      return authBusinessError(1001, "验证码错误或已过期");
    }
    user = await dependencies.accounts.findByEmailOrUsername(email);
    if (!user) {
      const generatedPassword = Array.from(
        crypto.getRandomValues(new Uint8Array(32)),
        (byte) => byte.toString(16).padStart(2, "0"),
      ).join("");
      user = await dependencies.accounts.createAccount(email, generatedPassword, email.split("@")[0], pepper);
      if (!user) user = await dependencies.accounts.findByEmailOrUsername(email);
    }
  } else {
    if (typeof input.password !== "string" || input.password.length < 1 || input.password.length > 256) {
      return authBusinessError(1001, "用户名或密码错误");
    }
    user = await dependencies.accounts.authenticatePassword(account, input.password, pepper);
    if (!user) return authBusinessError(1001, "用户名或密码错误；旧账号请先重置密码");
  }
  if (!user) return apiError(503, "账号暂时无法创建");
  if (user.status !== 1) return authBusinessError(1001, "账号已被禁用，请联系管理员");
  await dependencies.grantRegistrationPoints(user.id);
  return dependencies.createTokenResponse(user);
}

async function register(
  request: Request,
  dependencies: IdentityAccountControllerDependencies,
): Promise<Response> {
  const pepper = configuredPepper(dependencies.passwordPepper);
  if (!pepper) return apiError(503, "身份服务尚未配置");
  const body = await readJsonObject(request, MAX_BODY_BYTES);
  if (body instanceof Response) return body;
  const email = normalizedEmail(body.email);
  if (!email) return authBusinessError(400, "邮箱格式不正确");
  if (typeof body.password !== "string" || body.password.length < 6 || body.password.length > 50) {
    return authBusinessError(400, "密码长度为 6-50 个字符");
  }
  if (typeof body.name !== "string" || !body.name.trim() || body.name.trim().length > 50) {
    return authBusinessError(400, "用户名不能为空或超过 50 个字符");
  }
  const limited = await dependencies.enforceLimits(request, [
    { namespace: "auth-register-email", identifier: email, limit: 1 },
    { namespace: "auth-register-ip", identifier: requestIp(request), limit: 5 },
  ]);
  if (limited) return limited;
  if (await dependencies.accounts.findByEmailOrUsername(email)) return authBusinessError(400, "该邮箱已注册");
  const user = await dependencies.accounts.createAccount(email, body.password, body.name.trim(), pepper);
  if (!user) return authBusinessError(400, "该邮箱已注册");
  await dependencies.grantRegistrationPoints(user.id);
  return dependencies.createTokenResponse(user);
}

async function resetPassword(
  request: Request,
  dependencies: IdentityAccountControllerDependencies,
): Promise<Response> {
  const pepper = configuredPepper(dependencies.passwordPepper);
  if (!pepper) return apiError(503, "身份服务尚未配置");
  const body = await readJsonObject(request, MAX_BODY_BYTES);
  if (body instanceof Response) return body;
  const email = normalizedEmail(body.email);
  if (!email || typeof body.code !== "string" || !/^\d{6}$/.test(body.code.trim())) {
    return authBusinessError(400, "邮箱或验证码格式不正确");
  }
  if (typeof body.newPassword !== "string" || body.newPassword.length < 6 || body.newPassword.length > 72) {
    return authBusinessError(400, "新密码长度需在 6-72 个字符之间");
  }
  const limited = await dependencies.enforceLimits(request, [
    { namespace: "auth-reset-email", identifier: email, limit: 5 },
    { namespace: "auth-reset-ip", identifier: requestIp(request), limit: 10 },
  ]);
  if (limited) return limited;
  if (!await dependencies.verificationCodes.verify(email, body.code.trim())) {
    return authBusinessError(400, "验证码错误或已过期");
  }
  const user = await dependencies.accounts.findByEmailOrUsername(email);
  if (!user) return authBusinessError(400, "该邮箱尚未绑定账户");
  await dependencies.accounts.resetPassword(user.id, body.newPassword, pepper);
  return success(null);
}

async function changePassword(
  request: Request,
  user: IdentityUser,
  dependencies: IdentityAccountControllerDependencies,
): Promise<Response> {
  const pepper = configuredPepper(dependencies.passwordPepper);
  if (!pepper) return apiError(503, "身份服务尚未配置");
  const body = await readJsonObject(request, MAX_BODY_BYTES);
  if (body instanceof Response) return body;
  if (typeof body.currentPassword !== "string"
    || typeof body.newPassword !== "string"
    || body.newPassword.length < 6 || body.newPassword.length > 72) {
    return authBusinessError(400, "密码格式不正确");
  }
  const limited = await dependencies.enforceLimits(request, [
    { namespace: "auth-password-change-user", identifier: String(user.id), limit: 5 },
    { namespace: "auth-password-change-ip", identifier: requestIp(request), limit: 10 },
  ]);
  if (limited) return limited;
  if (!await dependencies.accounts.changePassword(user.id, body.currentPassword, body.newPassword, pepper)) {
    return authBusinessError(400, "当前密码不正确；旧账号请使用邮箱重置");
  }
  return success(null);
}

export async function handleIdentityAccountRequest(
  request: Request,
  dependencies: IdentityAccountControllerDependencies,
): Promise<Response | null> {
  const url = new URL(request.url);
  if (request.method === "GET" && url.pathname === "/api/auth/code") {
    const email = normalizedEmail(url.searchParams.get("email"));
    return email ? sendVerificationCode(request, email, dependencies) : apiError(400, "邮箱格式不正确");
  }
  if (request.method === "POST" && url.pathname === "/api/auth/login") {
    return login(request, dependencies);
  }
  if (request.method === "POST" && url.pathname === "/api/auth/register") {
    return register(request, dependencies);
  }
  if (request.method === "POST" && url.pathname === "/api/auth/reset-password") {
    return resetPassword(request, dependencies);
  }
  if (request.method === "PUT" && url.pathname === "/api/auth/password") {
    const user = await dependencies.resolveUser(request);
    return user instanceof Response ? user : changePassword(request, user, dependencies);
  }
  return null;
}
