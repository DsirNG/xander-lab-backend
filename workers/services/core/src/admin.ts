import { apiError, jsonResponse } from "@dinqor/worker-runtime";
import type { ModelProviderRecord } from "./interfaces/model-provider-repository.js";
import type { FeatureModelConfigRecord } from "./interfaces/feature-model-config-repository.js";
import { ModelProviderService, type ModelProviderMutation } from "./services/model-provider-service.js";
import { FeatureModelConfigService, MODEL_FEATURES } from "./services/feature-model-config-service.js";
import { WechatCredentialService } from "./services/wechat-credential-service.js";
import { VirtualPaySettingsService } from "./services/virtual-pay-settings-service.js";

export { decryptSecret } from "./services/secret-service.js";

export interface AdminUser {
  id: number;
  role: string;
}

export interface AdminEnvironment {
  ADMIN_MODEL_GATEWAY_ENABLED?: string;
  MODEL_PROVIDER_ENCRYPTION_KEY?: string;
  WECHAT_CREDENTIAL_ENCRYPTION_KEY?: string;
  resolveUser?: UserResolver;
}

type UserResolver = (request: Request) => Promise<AdminUser | Response>;
type JsonObject = Record<string, unknown>;

const MAX_BODY_BYTES = 32 * 1024;

function success(data: unknown): Response {
  return jsonResponse({ code: 200, message: "success", data });
}

async function readObject(request: Request): Promise<JsonObject | Response> {
  const declaredLength = request.headers.get("content-length");
  if (declaredLength && Number(declaredLength) > MAX_BODY_BYTES) return apiError(413, "请求内容过大");
  if (!request.body) return apiError(400, "请求内容格式不正确");
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let length = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      length += value.byteLength;
      if (length > MAX_BODY_BYTES) {
        await reader.cancel();
        return apiError(413, "请求内容过大");
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const bytes = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  try {
    const value: unknown = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
    return value && typeof value === "object" && !Array.isArray(value)
      ? value as JsonObject
      : apiError(400, "请求内容格式不正确");
  } catch {
    return apiError(400, "请求内容格式不正确");
  }
}


async function requireAdmin(request: Request, resolveUser: UserResolver): Promise<Response | null> {
  const user = await resolveUser(request);
  if (user instanceof Response) return user;
  return user.role === "ADMIN" ? null : apiError(403, "无权访问");
}

function text(value: unknown): string | null {
  return typeof value === "string" ? value.trim() : null;
}

function positiveId(value: unknown): number | null {
  if (value === null || value === undefined || value === "") return null;
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 1) return NaN;
  return value;
}

function modelGatewayEnabled(env: AdminEnvironment): boolean {
  return env.ADMIN_MODEL_GATEWAY_ENABLED === "true";
}

function providerView(row: ModelProviderRecord) {
  return {
    id: Number(row.id),
    name: row.name,
    vendor: row.vendor,
    baseUrl: row.baseUrl,
    apiKeyConfigured: Boolean(row.encryptedApiKey),
    description: row.description,
    enabled: Boolean(row.enabled),
    priority: Number(row.priority ?? 0),
    createdAt: row.createdAt,
    updatedAt: row.updatedAt,
  };
}

function providerMutationError(result: ModelProviderMutation): Response | null {
  if (result.kind === "duplicate") return apiError(400, "供应商名称已存在");
  if (result.kind === "encryption_unavailable") return apiError(503, "模型供应商加密密钥未正确配置");
  if (result.kind === "save_failed") return apiError(503, "供应商保存失败");
  if (result.kind === "missing") return apiError(404, "供应商不存在");
  return null;
}

async function modelProviders(
  request: Request,
  env: AdminEnvironment,
  url: URL,
  service: ModelProviderService,
): Promise<Response | null> {
  const collection = url.pathname === "/api/admin/model-providers";
  const enabledMatch = url.pathname.match(/^\/api\/admin\/model-providers\/(\d+)\/enabled$/);
  const itemMatch = url.pathname.match(/^\/api\/admin\/model-providers\/(\d+)$/);
  if (!collection && !enabledMatch && !itemMatch) return null;
  const methods = collection ? ["GET", "POST"] : enabledMatch ? ["PATCH"] : ["PUT", "DELETE"];
  if (!methods.includes(request.method)) return null;
  const auth = await requireAdmin(request, env.resolveUser!);
  if (auth) return auth;
  if (!modelGatewayEnabled(env)) {
    return apiError(503, "模型供应商管理尚未启用，请配置 ADMIN_MODEL_GATEWAY_ENABLED=true 并应用数据库迁移");
  }

  if (collection && request.method === "GET") {
    return success((await service.list()).map(providerView));
  }

  if (collection && request.method === "POST") {
    const body = await readObject(request);
    if (body instanceof Response) return body;
    const name = text(body.name);
    const vendor = text(body.vendor);
    const baseUrl = text(body.baseUrl);
    const apiKey = text(body.apiKey);
    const description = text(body.description);
    const enabled = body.enabled === undefined ? true : body.enabled;
    const priority = body.priority === undefined ? 0 : body.priority;
    if (!name || name.length > 100) return apiError(400, "供应商名称不能为空且不超过 100 个字符");
    if (!vendor || vendor.length > 50) return apiError(400, "协议类型不能为空");
    if (!baseUrl || (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://"))) {
      return apiError(400, "API 基础地址必须以 http(s):// 开头");
    }
    if (baseUrl.length > 255) return apiError(400, "API 基础地址过长");
    if (description && description.length > 500) return apiError(400, "备注不能超过 500 个字符");
    if (!apiKey) return apiError(400, "API 密钥不能为空");
    if (apiKey.length > 4096) return apiError(400, "API 密钥过长");
    if (typeof enabled !== "boolean") return apiError(400, "enabled 必须为布尔值");
    if (typeof priority !== "number" || !Number.isSafeInteger(priority)
      || priority < -2_147_483_648 || priority > 2_147_483_647) return apiError(400, "priority 格式不正确");
    const result = await service.create({
      name,
      vendor: vendor.toUpperCase() === "OPENAI_COMPATIBLE" ? "OPENAI_COMPATIBLE" : vendor,
      baseUrl,
      apiKey,
      description: description || null,
      enabled,
      priority,
    }, env.MODEL_PROVIDER_ENCRYPTION_KEY);
    const error = providerMutationError(result);
    if (error) return error;
    return result.kind === "saved" ? success(providerView(result.provider)) : apiError(503, "供应商保存失败");
  }

  const id = Number((enabledMatch ?? itemMatch)?.[1]);
  if (!Number.isSafeInteger(id) || id < 1) return apiError(400, "供应商 ID 格式不正确");

  if (enabledMatch) {
    const body = await readObject(request);
    if (body instanceof Response) return body;
    if (typeof body.enabled !== "boolean") return apiError(400, "enabled 必须为布尔值");
    const result = await service.setEnabled(id, body.enabled);
    const error = providerMutationError(result);
    if (error) return error;
    return result.kind === "saved" ? success(providerView(result.provider)) : apiError(404, "供应商不存在");
  }

  if (itemMatch && request.method === "DELETE") {
    const result = await service.delete(id);
    if (result.kind === "missing") return apiError(404, "供应商不存在");
    if (result.kind === "referenced") {
      return apiError(409, "该供应商仍被功能模型配置引用，请先移除相关配置再删除");
    }
    return success(null);
  }

  const body = await readObject(request);
  if (body instanceof Response) return body;
  const name = text(body.name);
  const vendor = text(body.vendor);
  const baseUrl = text(body.baseUrl);
  const description = text(body.description);
  const apiKey = text(body.apiKey);
  const enabled = body.enabled === undefined ? true : body.enabled;
  const priority = body.priority === undefined ? 0 : body.priority;
  if (!name || name.length > 100) return apiError(400, "供应商名称不能为空且不超过 100 个字符");
  if (!vendor || vendor.length > 50) return apiError(400, "协议类型不能为空");
  if (!baseUrl || (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://"))) {
    return apiError(400, "API 基础地址必须以 http(s):// 开头");
  }
  if (baseUrl.length > 255) return apiError(400, "API 基础地址过长");
  if (description && description.length > 500) return apiError(400, "备注不能超过 500 个字符");
  if (apiKey && apiKey.length > 4096) return apiError(400, "API 密钥过长");
  if (typeof enabled !== "boolean") return apiError(400, "enabled 必须为布尔值");
  if (typeof priority !== "number" || !Number.isSafeInteger(priority)
    || priority < -2_147_483_648 || priority > 2_147_483_647) return apiError(400, "priority 格式不正确");
  const result = await service.update(id, {
    name,
    vendor: vendor.toUpperCase() === "OPENAI_COMPATIBLE" ? "OPENAI_COMPATIBLE" : vendor,
    baseUrl,
    apiKey: apiKey || "",
    description: description || null,
    enabled,
    priority,
  }, env.MODEL_PROVIDER_ENCRYPTION_KEY);
  const error = providerMutationError(result);
  if (error) return error;
  return result.kind === "saved" ? success(providerView(result.provider)) : apiError(404, "供应商不存在");
}

function featureView(row: FeatureModelConfigRecord) {
  return {
    featureKey: row.featureKey,
    enabled: Boolean(row.enabled),
    primaryProviderId: row.primaryProviderId == null ? null : Number(row.primaryProviderId),
    primaryModel: row.primaryModel ?? null,
    primaryApiStyle: row.primaryApiStyle ?? null,
    fallbackProviderId: row.fallbackProviderId == null ? null : Number(row.fallbackProviderId),
    fallbackModel: row.fallbackModel ?? null,
    fallbackApiStyle: row.fallbackApiStyle ?? null,
    updatedAt: row.updatedAt ?? null,
  };
}

async function featureConfigs(
  request: Request,
  env: AdminEnvironment,
  url: URL,
  service: FeatureModelConfigService,
): Promise<Response | null> {
  const collection = url.pathname === "/api/admin/feature-model-configs";
  const match = url.pathname.match(/^\/api\/admin\/feature-model-configs\/([^/]+)$/);
  if (!collection && !match) return null;
  if (!(collection && request.method === "GET") && !(match && request.method === "PUT")) return null;
  const auth = await requireAdmin(request, env.resolveUser!);
  if (auth) return auth;
  if (!modelGatewayEnabled(env)) {
    return apiError(503, "模型供应商管理尚未启用，请配置 ADMIN_MODEL_GATEWAY_ENABLED=true 并应用数据库迁移");
  }
  if (collection) {
    return success((await service.list()).map(featureView));
  }
  const featureKey = decodeURIComponent(match?.[1] ?? "");
  if (!MODEL_FEATURES.includes(featureKey)) return apiError(400, `未知的功能标识: ${featureKey}`);
  const body = await readObject(request);
  if (body instanceof Response) return body;
  const enabled = body.enabled === true;
  if (body.enabled !== undefined && typeof body.enabled !== "boolean") return apiError(400, "enabled 必须为布尔值");
  const primaryProviderId = positiveId(body.primaryProviderId);
  const fallbackProviderId = positiveId(body.fallbackProviderId);
  if (Number.isNaN(primaryProviderId) || Number.isNaN(fallbackProviderId)) return apiError(400, "供应商 ID 格式不正确");
  const primaryModelRaw = text(body.primaryModel);
  const fallbackModelRaw = text(body.fallbackModel);
  const primaryModel = primaryModelRaw || null;
  const fallbackModel = fallbackModelRaw || null;
  if (primaryModel && primaryModel.length > 100 || fallbackModel && fallbackModel.length > 100) {
    return apiError(400, "模型名不能超过 100 个字符");
  }
  const primaryApiStyleRaw = text(body.primaryApiStyle);
  const fallbackApiStyleRaw = text(body.fallbackApiStyle);
  const primaryApiStyle = primaryApiStyleRaw || null;
  const fallbackApiStyle = fallbackApiStyleRaw || null;
  const result = await service.save({
    featureKey,
    enabled,
    primaryProviderId,
    primaryModel,
    primaryApiStyle,
    fallbackProviderId,
    fallbackModel,
    fallbackApiStyle,
  });
  return result.kind === "saved"
    ? success(featureView(result.config))
    : apiError(400, result.message);
}

async function wechatCredential(
  request: Request,
  env: AdminEnvironment,
  url: URL,
  service: WechatCredentialService,
): Promise<Response | null> {
  if (url.pathname !== "/api/admin/wechat-credential"
    || !["GET", "PUT", "DELETE"].includes(request.method)) return null;
  const auth = await requireAdmin(request, env.resolveUser!);
  if (auth) return auth;
  if (request.method === "GET") return success(await service.adminView());
  if (request.method === "DELETE") {
    await service.deleteAdminCredential();
    return success(null);
  }
  const body = await readObject(request);
  if (body instanceof Response) return body;
  const appId = text(body.appId);
  const appSecret = text(body.appSecret);
  if (!appId) return apiError(400, "AppID 不能为空");
  if (appId.length > 100) return apiError(400, "AppID 过长");
  if (!appSecret) return apiError(400, "AppSecret 不能为空");
  if (appSecret.length > 200) return apiError(400, "AppSecret 过长");
  const result = await service.saveAdminCredential(appId, appSecret, env.WECHAT_CREDENTIAL_ENCRYPTION_KEY);
  if (result.kind === "unconfigured") return apiError(400, "微信加密密钥未配置，无法保存凭据");
  return result.kind === "saved" ? success(result.view) : apiError(503, "微信凭据保存失败");
}

function productInput(value: unknown): { productId: string; priceCents: number; durationDays: number } | null {
  if (value === undefined || value === null) return { productId: "", priceCents: 0, durationDays: 30 };
  if (typeof value !== "object" || Array.isArray(value)) return null;
  const row = value as JsonObject;
  const productId = row.productId === undefined || row.productId === null ? "" : text(row.productId);
  if (productId === null || productId.length > 100
    || typeof row.priceCents !== "number" || !Number.isSafeInteger(row.priceCents)
    || typeof row.durationDays !== "number" || !Number.isSafeInteger(row.durationDays)) return null;
  return { productId, priceCents: row.priceCents, durationDays: row.durationDays };
}

async function virtualPaySettings(
  request: Request,
  env: AdminEnvironment,
  url: URL,
  service: VirtualPaySettingsService,
): Promise<Response | null> {
  if (url.pathname !== "/api/admin/virtual-pay-settings" || !["GET", "PUT"].includes(request.method)) return null;
  const auth = await requireAdmin(request, env.resolveUser!);
  if (auth) return auth;
  if (request.method === "GET") return success(await service.get());
  const body = await readObject(request);
  if (body instanceof Response) return body;
  if (typeof body.enabled !== "boolean") return apiError(400, "enabled 必须为布尔值");
  const offerId = text(body.offerId);
  const appKey = text(body.appKey);
  if (offerId === null || offerId.length > 100) return apiError(400, "OfferID 格式不正确");
  if (appKey && appKey.length > 200) return apiError(400, "AppKey 过长");
  const plus = productInput(body.plus);
  const pro = productInput(body.pro);
  const ultra = productInput(body.ultra);
  if (!plus || !pro || !ultra) return apiError(400, "PLUS、PRO、ULTRA 商品配置格式不正确");
  const result = await service.save({ enabled: body.enabled, offerId, appKey: appKey ?? "", plus, pro, ultra },
    env.WECHAT_CREDENTIAL_ENCRYPTION_KEY);
  if (result.kind === "invalid") return apiError(400, result.message);
  return result.kind === "saved" ? success(result.view) : apiError(503, "虚拟支付配置保存失败");
}

export async function handleAdminSettingsRequest(
  request: Request,
  env: AdminEnvironment & { resolveUser: UserResolver },
  providers: ModelProviderService,
  featureModelConfigs: FeatureModelConfigService,
  wechatCredentials: WechatCredentialService,
  virtualPay: VirtualPaySettingsService,
): Promise<Response | null> {
  const url = new URL(request.url);
  return await modelProviders(request, env, url, providers)
    ?? await featureConfigs(request, env, url, featureModelConfigs)
    ?? await wechatCredential(request, env, url, wechatCredentials)
    ?? await virtualPaySettings(request, env, url, virtualPay);
}
