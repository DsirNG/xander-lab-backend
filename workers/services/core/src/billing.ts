import { apiError, jsonResponse, type ServiceEnvironment } from "@dinqor/worker-runtime";
import type { BillingLedgerInput, BillingModelPrice, BillingTokenPrice } from "./interfaces/billing-repository.js";
import { BillingService } from "./services/billing-service.js";

interface BillingEnvironment extends ServiceEnvironment {
  BILLING_ENABLED?: string;
  BILLING_GRANT_REGISTER?: string;
  BILLING_PRE_CHECK_MIN_BALANCE?: string;
  BILLING_INPUT_MILLI_PER_1K_TOKEN?: string;
  BILLING_OUTPUT_MILLI_PER_1K_TOKEN?: string;
  BILLING_CACHED_MILLI_PER_1K_TOKEN?: string;
  BILLING_MODEL_PRICES_JSON?: string;
  BILLING_FLAT_EMAIL_REMINDER?: string;
  BILLING_FLAT_CSDN_PUBLISH?: string;
  BILLING_FLAT_JUEJIN_PUBLISH?: string;
}

interface BillingUser {
  id: number;
  role: string;
}

type UserResolver = (request: Request) => Promise<BillingUser | Response>;

type TokenPrice = BillingTokenPrice;
type ModelPriceRow = BillingModelPrice;

interface LedgerRequest {
  userId?: unknown;
  bizType?: unknown;
  bizKey?: unknown;
  bizRef?: unknown;
  points?: unknown;
  inputTokens?: unknown;
  outputTokens?: unknown;
  cachedTokens?: unknown;
  model?: unknown;
  remark?: unknown;
}

const CREDIT_TYPES = new Set(["grant", "merge-credit"]);
const IMAGE_BIZ_TYPE = "blog-agent-illustration";
const DEFAULT_PRICE: TokenPrice = {
  inputMilliPer1kToken: 50,
  outputMilliPer1kToken: 200,
  cachedMilliPer1kToken: 5,
  cachedWriteMilliPer1kToken: 0,
};
const DEFAULT_MODEL_PRICES: Record<string, TokenPrice> = {
  "gpt-5.6-terra": {
    inputMilliPer1kToken: 20,
    outputMilliPer1kToken: 120,
    cachedMilliPer1kToken: 2,
    cachedWriteMilliPer1kToken: 25,
  },
  "gpt-image-2": {
    inputMilliPer1kToken: 50,
    outputMilliPer1kToken: 300,
    cachedMilliPer1kToken: 0,
    cachedWriteMilliPer1kToken: 0,
  },
};

function success(data: unknown): Response {
  return jsonResponse({ code: 200, message: "success", data });
}

function configuredInteger(value: string | undefined, fallback: number, minimum = 0): number {
  if (!value || !/^\d+$/.test(value)) return fallback;
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed >= minimum ? parsed : fallback;
}

function billingEnabled(env: BillingEnvironment): boolean {
  return env.BILLING_ENABLED !== "false";
}

function settings(env: BillingEnvironment) {
  return {
    enabled: billingEnabled(env),
    grantRegister: configuredInteger(env.BILLING_GRANT_REGISTER, 2_000_000),
    preCheckMinBalance: configuredInteger(env.BILLING_PRE_CHECK_MIN_BALANCE, 1_000),
    price: {
      ...DEFAULT_PRICE,
      inputMilliPer1kToken: configuredInteger(env.BILLING_INPUT_MILLI_PER_1K_TOKEN, 50),
      outputMilliPer1kToken: configuredInteger(env.BILLING_OUTPUT_MILLI_PER_1K_TOKEN, 200),
      cachedMilliPer1kToken: configuredInteger(env.BILLING_CACHED_MILLI_PER_1K_TOKEN, 5),
    },
    flat: {
      "email-reminder": configuredInteger(env.BILLING_FLAT_EMAIL_REMINDER, 5_000),
      "csdn-publish": configuredInteger(env.BILLING_FLAT_CSDN_PUBLISH, 10_000),
      "juejin-publish": configuredInteger(env.BILLING_FLAT_JUEJIN_PUBLISH, 10_000),
    } as Record<string, number>,
  };
}

function configuredModelPrices(env: BillingEnvironment): Record<string, TokenPrice> {
  const configured = { ...DEFAULT_MODEL_PRICES };
  if (!env.BILLING_MODEL_PRICES_JSON) return configured;
  try {
    const parsed: unknown = JSON.parse(env.BILLING_MODEL_PRICES_JSON);
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return configured;
    for (const [model, raw] of Object.entries(parsed)) {
      if (!model || model.length > 128 || !raw || typeof raw !== "object" || Array.isArray(raw)) continue;
      const entry = raw as Record<string, unknown>;
      const fields = [
        entry.inputMilliPer1kToken,
        entry.outputMilliPer1kToken,
        entry.cachedMilliPer1kToken ?? 0,
        entry.cachedWriteMilliPer1kToken ?? 0,
      ];
      if (!fields.every((value) => typeof value === "number"
        && Number.isSafeInteger(value) && value >= 0)) continue;
      configured[model] = {
        inputMilliPer1kToken: fields[0] as number,
        outputMilliPer1kToken: fields[1] as number,
        cachedMilliPer1kToken: fields[2] as number,
        cachedWriteMilliPer1kToken: fields[3] as number,
      };
    }
  } catch {
    return configured;
  }
  return configured;
}

function validIdentifier(value: unknown, maxLength: number): value is string {
  return typeof value === "string" && value.trim().length > 0 && value.length <= maxLength;
}

function safePositiveInteger(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value > 0;
}

function safeNonNegativeInteger(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0;
}

async function readJson(request: Request): Promise<Record<string, unknown> | Response> {
  const length = request.headers.get("content-length");
  if (length && Number(length) > 16 * 1024) return apiError(413, "请求内容过大");
  try {
    const body: unknown = await request.json();
    if (!body || typeof body !== "object" || Array.isArray(body)) {
      return apiError(400, "请求内容格式不正确");
    }
    return body as Record<string, unknown>;
  } catch {
    return apiError(400, "请求内容格式不正确");
  }
}

function resolvedPrice(model: string | null, dbPrice: ModelPriceRow | null, env: BillingEnvironment): TokenPrice {
  if (dbPrice?.enabled === 1) return dbPrice;
  const fromConfig = model ? configuredModelPrices(env)[model] : undefined;
  if (fromConfig) return fromConfig;
  return settings(env).price;
}

function tokenCost(usage: { inputTokens: number; outputTokens: number; cachedTokens: number }, price: TokenPrice): number {
  const input = Math.max(0, usage.inputTokens);
  const output = Math.max(0, usage.outputTokens);
  const cached = Math.min(input, Math.max(0, usage.cachedTokens));
  const cost = Math.round(
    ((input - cached) * price.inputMilliPer1kToken
      + output * price.outputMilliPer1kToken
      + cached * price.cachedMilliPer1kToken) / 1_000,
  );
  return Math.max(1, Math.min(Number.MAX_SAFE_INTEGER, cost));
}

function priceSnapshot(price: TokenPrice, model: string | null): string {
  return `${price.inputMilliPer1kToken}/${price.outputMilliPer1kToken}/${price.cachedMilliPer1kToken}${model ? ` ${model}` : ""}`;
}

async function appendLedger(
  env: BillingEnvironment,
  billing: BillingService,
  entry: {
    userId: number;
    bizType: string;
    bizKey: string;
    bizRef: string | null;
    amount: number;
    model: string | null;
    inputTokens: number | null;
    outputTokens: number | null;
    cachedTokens: number | null;
    remark: string | null;
    price: TokenPrice;
  },
): Promise<void> {
  if (!billingEnabled(env) || entry.amount <= 0) return;
  const credit = CREDIT_TYPES.has(entry.bizType);
  const ledger: BillingLedgerInput = {
    userId: entry.userId,
    bizType: entry.bizType,
    bizKey: entry.bizKey,
    bizRef: entry.bizRef,
    amount: entry.amount,
    balanceDelta: credit ? entry.amount : -entry.amount,
    unitPrice: priceSnapshot(entry.price, entry.model),
    model: entry.model,
    inputTokens: entry.inputTokens,
    outputTokens: entry.outputTokens,
    cachedTokens: entry.cachedTokens,
    remark: entry.remark,
  };
  await billing.appendLedger(ledger);
}

/** Applies a one-time welcome grant only to accounts created by the Worker. */
export async function grantRegistrationPoints(
  env: BillingEnvironment,
  billing: BillingService,
  userId: number,
): Promise<void> {
  const grant = settings(env).grantRegister;
  await billing.grantRegistrationPoints(
    userId,
    grant,
    priceSnapshot(settings(env).price, null),
    billingEnabled(env),
  );
}

async function pointsOverview(env: BillingEnvironment, billing: BillingService, userId: number): Promise<Response> {
  const [balance, consumed] = await Promise.all([
    billing.balance(userId),
    billing.consumedToday(userId),
  ]);
  const config = settings(env);
  return success({
    unit: "milli-point",
    balance,
    consumedToday: consumed,
    enablePreCheck: config.enabled && balance < config.preCheckMinBalance,
  });
}

async function pageLedger(request: Request, billing: BillingService, userId: number): Promise<Response> {
  const url = new URL(request.url);
  const pageValue = Number(url.searchParams.get("page") ?? "1");
  const sizeValue = Number(url.searchParams.get("size") ?? "20");
  const page = Number.isSafeInteger(pageValue) ? Math.max(1, pageValue) : 1;
  const size = Number.isSafeInteger(sizeValue) ? Math.min(100, Math.max(1, sizeValue)) : 20;
  const offset = (page - 1) * size;
  if (!Number.isSafeInteger(offset)) return apiError(400, "分页参数超出范围");
  const pageResult = await billing.pageLedger(userId, size, offset);
  const records = pageResult.records.map((row) => {
    const bizType = String(row.bizType);
    const amount = Number(row.amount);
    const credit = CREDIT_TYPES.has(bizType);
    return {
      ...row,
      delta: credit ? amount : -amount,
      direction: credit ? "credit" : "debit",
    };
  });
  return success({ records, total: pageResult.total });
}

async function publicPriceList(env: BillingEnvironment, billing: BillingService): Promise<Response> {
  const config = settings(env);
  const models: Record<string, unknown> = {};
  for (const row of await billing.listModelPrices()) {
    models[row.model] = {
      source: "db",
      enabled: row.enabled === 1,
      inputMilliPer1kToken: row.inputMilliPer1kToken,
      outputMilliPer1kToken: row.outputMilliPer1kToken,
      cachedMilliPer1kToken: row.cachedMilliPer1kToken,
      cachedWriteMilliPer1kToken: row.cachedWriteMilliPer1kToken,
    };
  }
  for (const [model, price] of Object.entries(configuredModelPrices(env))) {
    if (models[model]) continue;
    models[model] = { source: "yml", ...price };
  }
  return success({
    unit: "milli-point",
    grantRegister: config.grantRegister,
    preCheckMinBalance: config.preCheckMinBalance,
    token: {
      inputMilliPer1kToken: config.price.inputMilliPer1kToken,
      outputMilliPer1kToken: config.price.outputMilliPer1kToken,
      cachedMilliPer1kToken: config.price.cachedMilliPer1kToken,
    },
    models,
    flat: config.flat,
  });
}

function modelPriceView(row: ModelPriceRow) {
  return {
    model: row.model,
    inputMilliPer1kToken: row.inputMilliPer1kToken,
    outputMilliPer1kToken: row.outputMilliPer1kToken,
    cachedMilliPer1kToken: row.cachedMilliPer1kToken,
    cachedWriteMilliPer1kToken: row.cachedWriteMilliPer1kToken,
    enabled: row.enabled === 1,
    remark: row.remark,
    updatedAt: row.updatedAt,
  };
}

async function adminModelPrices(
  request: Request,
  billing: BillingService,
  user: BillingUser,
): Promise<Response | null> {
  if (user.role !== "ADMIN") return apiError(403, "无权访问");
  const url = new URL(request.url);
  const base = "/api/admin/billing/model-prices";
  if (request.method === "GET" && url.pathname === base) {
    return success((await billing.listModelPrices()).map(modelPriceView));
  }

  const match = url.pathname.match(/^\/api\/admin\/billing\/model-prices\/([^/]+)$/);
  if (!match) return null;
  let model: string;
  try {
    model = decodeURIComponent(match[1]).trim();
  } catch {
    return apiError(400, "模型名称格式不正确");
  }
  if (!model || model.length > 128) return apiError(400, "模型名称不能为空或过长");
  if (request.method === "DELETE") {
    await billing.deleteModelPrice(model);
    return success(null);
  }
  if (request.method !== "PUT") return null;
  const body = await readJson(request);
  if (body instanceof Response) return body;
  const input = body.inputMilliPer1kToken;
  const output = body.outputMilliPer1kToken;
  const cached = body.cachedMilliPer1kToken === undefined ? 0 : body.cachedMilliPer1kToken;
  const cachedWrite = body.cachedWriteMilliPer1kToken === undefined ? 0 : body.cachedWriteMilliPer1kToken;
  const enabled = body.enabled === undefined ? true : body.enabled;
  if (![input, output, cached, cachedWrite].every(safeNonNegativeInteger)
    || typeof enabled !== "boolean"
    || (body.remark !== undefined && body.remark !== null
      && (typeof body.remark !== "string" || body.remark.length > 255))) {
    return apiError(400, "模型定价参数格式不正确");
  }
  const inputPrice = input as number;
  const outputPrice = output as number;
  const cachedPrice = cached as number;
  const cachedWritePrice = cachedWrite as number;
  const remark = typeof body.remark === "string" ? body.remark : null;
  const row = await billing.upsertModelPrice({
    model,
    inputMilliPer1kToken: inputPrice,
    outputMilliPer1kToken: outputPrice,
    cachedMilliPer1kToken: cachedPrice,
    cachedWriteMilliPer1kToken: cachedWritePrice,
    enabled: enabled as boolean,
    remark,
  });
  return row ? success(modelPriceView(row)) : apiError(503, "模型定价暂不可用");
}

async function chargeTokens(
  env: BillingEnvironment,
  billing: BillingService,
  body: Record<string, unknown>,
): Promise<Response> {
  if (!safePositiveInteger(body.userId)
    || !validIdentifier(body.bizType, 32)
    || !validIdentifier(body.bizKey, 128)
    || (body.bizRef !== undefined && body.bizRef !== null && !validIdentifier(body.bizRef, 128))
    || !safeNonNegativeInteger(body.inputTokens)
    || !safeNonNegativeInteger(body.outputTokens)
    || !safeNonNegativeInteger(body.cachedTokens)
    || (body.model !== undefined && body.model !== null && !validIdentifier(body.model, 128))
    || (body.remark !== undefined && body.remark !== null
      && (typeof body.remark !== "string" || body.remark.length > 500))) {
    return apiError(400, "计费参数格式不正确");
  }
  if (!billingEnabled(env)) return success(null);
  const userId = body.userId as number;
  const bizType = body.bizType as string;
  const bizKey = body.bizKey as string;
  const usage = {
    inputTokens: body.inputTokens as number,
    outputTokens: body.outputTokens as number,
    cachedTokens: body.cachedTokens as number,
  };
  const model = typeof body.model === "string" ? body.model : null;
  const dbPrice = await billing.findModelPrice(model);
  const price = resolvedPrice(model, dbPrice, env);
  const empty = usage.inputTokens === 0 && usage.outputTokens === 0 && usage.cachedTokens === 0;
  if (empty) {
    if (bizType === IMAGE_BIZ_TYPE) return apiError(400, "图片模型未返回 token 用量，无法按 token 计费");
    const fallback = settings(env).flat[bizType] ?? 0;
    if (fallback > 0) {
      await appendLedger(env, billing, {
        userId,
        bizType,
        bizKey,
        bizRef: typeof body.bizRef === "string" ? body.bizRef : null,
        amount: fallback,
        model: null,
        inputTokens: null,
        outputTokens: null,
        cachedTokens: null,
        remark: typeof body.remark === "string" ? `${body.remark}（网关未返回用量，按一口价）` : "网关未返回用量，按一口价",
        price,
      });
    }
    return success(null);
  }
  await appendLedger(env, billing, {
    userId,
    bizType,
    bizKey,
    bizRef: typeof body.bizRef === "string" ? body.bizRef : null,
    amount: tokenCost(usage, price),
    model,
    inputTokens: usage.inputTokens,
    outputTokens: usage.outputTokens,
    cachedTokens: usage.cachedTokens,
    remark: typeof body.remark === "string" ? body.remark : null,
    price,
  });
  return success(null);
}

async function internalBilling(
  request: Request,
  env: BillingEnvironment,
  billing: BillingService,
): Promise<Response | null> {
  const path = new URL(request.url).pathname;
  if (!path.startsWith("/internal/agent/billing/")) return null;
  if (request.method !== "POST") return apiError(404, "Not Found");
  const body = await readJson(request);
  if (body instanceof Response) return body;
  switch (path) {
    case "/internal/agent/billing/charge-tokens":
      return chargeTokens(env, billing, body);
    case "/internal/agent/billing/charge-flat": {
      if (!safePositiveInteger(body.userId) || !validIdentifier(body.bizType, 32)
        || !validIdentifier(body.bizKey, 128)
        || (body.bizRef !== undefined && body.bizRef !== null && !validIdentifier(body.bizRef, 128))
        || !safePositiveInteger(body.points)
        || (body.remark !== undefined && body.remark !== null
          && (typeof body.remark !== "string" || body.remark.length > 500))) {
        return apiError(400, "计费参数格式不正确");
      }
      const row = await billing.findModelPrice(null);
      await appendLedger(env, billing, {
        userId: body.userId,
        bizType: body.bizType,
        bizKey: body.bizKey,
        bizRef: typeof body.bizRef === "string" ? body.bizRef : null,
        amount: body.points,
        model: null,
        inputTokens: null,
        outputTokens: null,
        cachedTokens: null,
        remark: typeof body.remark === "string" ? body.remark : null,
        price: resolvedPrice(null, row, env),
      });
      return success(null);
    }
    case "/internal/agent/billing/flat-points": {
      if (!validIdentifier(body.bizType, 32)) return apiError(400, "业务类型不能为空");
      return success({ points: settings(env).flat[String(body.bizType)] ?? 0 });
    }
    case "/internal/agent/billing/ensure-available": {
      if (!safePositiveInteger(body.userId)) return apiError(400, "用户 ID 格式不正确");
      if (!billingEnabled(env)) return success(null);
       const balance = await billing.balance(body.userId);
      if (balance < settings(env).preCheckMinBalance) return apiError(402, "积分不足，请先获取积分");
      return success(null);
    }
    case "/internal/agent/billing/balance": {
      if (!safePositiveInteger(body.userId)) return apiError(400, "用户 ID 格式不正确");
       return success({ balance: await billing.balance(body.userId) });
    }
    case "/internal/agent/billing/grant-register": {
      if (!safePositiveInteger(body.userId) || !validIdentifier(body.bizKey, 128)) {
        return apiError(400, "注册赠送参数格式不正确");
      }
       await grantByKey(env, billing, body.userId, body.bizKey);
      return success(null);
    }
    case "/internal/agent/billing/transfer-balance":
       return transferBalance(env, billing, body);
    default:
      return apiError(404, "Not Found");
  }
}

async function grantByKey(
  env: BillingEnvironment,
  billing: BillingService,
  userId: number,
  bizKey: string,
): Promise<void> {
  const amount = settings(env).grantRegister;
  if (!billingEnabled(env) || amount <= 0) return;
  const price = settings(env).price;
  await billing.grantByKey(userId, bizKey, amount, priceSnapshot(price, null));
}

async function transferBalance(
  env: BillingEnvironment,
  billing: BillingService,
  body: Record<string, unknown>,
): Promise<Response> {
  if (!safePositiveInteger(body.fromUserId) || !safePositiveInteger(body.toUserId)
    || !validIdentifier(body.bizKey, 128)
    || (body.remark !== undefined && body.remark !== null
      && (typeof body.remark !== "string" || body.remark.length > 500))) {
    return apiError(400, "账号合并参数格式不正确");
  }
  if (!billingEnabled(env) || body.fromUserId === body.toUserId) return success(null);
  await billing.transferBalance(
    body.fromUserId,
    body.toUserId,
    body.bizKey,
    priceSnapshot(settings(env).price, null),
    typeof body.remark === "string" ? body.remark : "账号合并转入",
  );
  return success(null);
}

/** Handles public points APIs, admin rate management, and private Agent billing calls. */
export async function handleBillingRequest(
  request: Request,
  env: BillingEnvironment,
  billing: BillingService,
  resolveUser: UserResolver,
): Promise<Response | null> {
  const url = new URL(request.url);
  const internal = await internalBilling(request, env, billing);
  if (internal) return internal;
  if (url.pathname === "/api/points" && request.method === "GET") {
    const user = await resolveUser(request);
    return user instanceof Response ? user : pointsOverview(env, billing, user.id);
  }
  if (url.pathname === "/api/points/ledger" && request.method === "GET") {
    const user = await resolveUser(request);
    return user instanceof Response ? user : pageLedger(request, billing, user.id);
  }
  if (url.pathname === "/api/points/prices" && request.method === "GET") {
    const user = await resolveUser(request);
    return user instanceof Response ? user : publicPriceList(env, billing);
  }
  if (url.pathname === "/api/admin/billing/model-prices"
    || url.pathname.startsWith("/api/admin/billing/model-prices/")) {
    const user = await resolveUser(request);
    return user instanceof Response ? user : adminModelPrices(request, billing, user);
  }
  return null;
}

export const billingInternals = {
  tokenCost,
  settings,
  configuredModelPrices,
};
