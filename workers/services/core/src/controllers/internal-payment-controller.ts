import { apiError, jsonResponse } from "@dinqor/worker-runtime";
import type { PaymentEntitlementService } from "../services/payment-entitlement-service.js";
import type { WechatCredentialService } from "../services/wechat-credential-service.js";
import type { WechatPaymentIdentityService } from "../services/wechat-payment-identity-service.js";

const MAX_BODY_BYTES = 16 * 1024;

interface PaymentContractEnvironment {
  WECHAT_MINIAPP_APP_ID?: string;
  WECHAT_MINIAPP_APP_SECRET?: string;
  WECHAT_MINIAPP_CODE2SESSION_URL?: string;
  WECHAT_CREDENTIAL_ENCRYPTION_KEY?: string;
}

function success(data: unknown): Response {
  return jsonResponse({ code: 200, message: "success", data });
}

function object(value: unknown): Record<string, unknown> | null {
  return value && typeof value === "object" && !Array.isArray(value)
    ? value as Record<string, unknown> : null;
}

async function bodyObject(request: Request): Promise<Record<string, unknown> | Response> {
  const length = request.headers.get("content-length");
  if (length && Number(length) > MAX_BODY_BYTES) return apiError(413, "请求内容过大");
  try {
    const raw = await request.text();
    if (new TextEncoder().encode(raw).byteLength > MAX_BODY_BYTES) return apiError(413, "请求内容过大");
    const value = object(JSON.parse(raw));
    return value ?? apiError(400, "请求内容格式不正确");
  } catch {
    return apiError(400, "请求内容格式不正确");
  }
}

function positiveInteger(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value > 0;
}

export interface InternalPaymentDependencies {
  environment: PaymentContractEnvironment;
  credentials: WechatCredentialService;
  paymentIdentity: WechatPaymentIdentityService;
  entitlements: PaymentEntitlementService;
}

export async function handleInternalPaymentRequest(
  request: Request,
  dependencies: InternalPaymentDependencies,
): Promise<Response | null> {
  const path = new URL(request.url).pathname;
  if (!path.startsWith("/internal/v1/payment/")) return null;
  if (request.method !== "POST") return apiError(404, "Not Found");
  const body = await bodyObject(request);
  if (body instanceof Response) return body;

  if (path === "/internal/v1/payment/identity/resolve") {
    if (!positiveInteger(body.userId) || typeof body.loginCode !== "string") {
      return apiError(400, "微信支付身份请求格式不正确");
    }
    const result = await dependencies.paymentIdentity.resolve(body.userId, body.loginCode, {
      fallbackAppId: dependencies.environment.WECHAT_MINIAPP_APP_ID,
      fallbackAppSecret: dependencies.environment.WECHAT_MINIAPP_APP_SECRET,
      encryptionKey: dependencies.environment.WECHAT_CREDENTIAL_ENCRYPTION_KEY,
      endpoint: dependencies.environment.WECHAT_MINIAPP_CODE2SESSION_URL,
    });
    if (result.kind === "success") return success({ openid: result.openid, sessionKey: result.sessionKey });
    if (result.kind === "account_disabled") return apiError(401, "账号已停用");
    if (result.kind === "account_unbound") return apiError(400, "当前账号未绑定微信小程序身份");
    if (result.kind === "identity_mismatch") return apiError(400, "微信支付身份校验失败，请重新登录后再试");
    if (result.kind === "invalid_code") return apiError(400, "微信登录凭证无效");
    if (result.kind === "unconfigured") return apiError(503, "微信登录未配置，请联系管理员");
    if (result.kind === "credential_invalid") return apiError(503, "微信登录加密凭据不可用");
    if (result.kind === "invalid_configuration") return apiError(503, "微信登录服务配置无效");
    return apiError(503, "微信登录服务暂不可用，请稍后重试");
  }

  if (path === "/internal/v1/payment/wechat/credentials") {
    const credential = await dependencies.credentials.resolve(
      dependencies.environment.WECHAT_MINIAPP_APP_ID,
      dependencies.environment.WECHAT_MINIAPP_APP_SECRET,
      dependencies.environment.WECHAT_CREDENTIAL_ENCRYPTION_KEY,
    );
    if (credential.kind !== "configured") return apiError(503, "微信小程序凭据未配置");
    return success({ appId: credential.appId, appSecret: credential.appSecret });
  }

  if (path === "/internal/v1/payment/entitlements/grant") {
    if (!positiveInteger(body.userId) || typeof body.tier !== "string"
      || !Number.isSafeInteger(body.durationDays) || typeof body.durationDays !== "number"
      || typeof body.eventKey !== "string") return apiError(400, "订阅权益请求格式不正确");
    try {
      const inserted = await dependencies.entitlements.grantOnce(
        body.userId, body.tier, body.durationDays, body.eventKey,
      );
      return success({ accepted: true, firstGrant: inserted });
    } catch {
      return apiError(400, "订阅权益请求格式不正确");
    }
  }
  return apiError(404, "Not Found");
}
