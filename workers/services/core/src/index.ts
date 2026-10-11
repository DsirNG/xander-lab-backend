import {
  apiError,
  createBusinessService,
  jsonResponse,
  type D1Binding,
  type ServiceEnvironment,
  type WorkerServiceBinding,
} from "@dinqor/worker-runtime";
import { signJwtToken } from "./auth.js";
import { handleAdminSettingsRequest } from "./admin.js";
import { handleIdentityAuthRequest, resolveCurrentUser } from "./controllers/identity-auth-controller.js";
import { handleIdentitySessionRequest } from "./controllers/identity-session-controller.js";
import { userInfo } from "./controllers/identity-view.js";
import {
  handleInternalAgentAuthResolve,
  handleInternalAgentModelConfig,
} from "./controllers/internal-agent-controller.js";
import { handleBlogAgentModelRequest } from "./controllers/blog-agent-model-controller.js";
import { handleAgentRecitationRequest } from "./controllers/agent-recitation-controller.js";
import { handleInternalPaymentRequest } from "./controllers/internal-payment-controller.js";
import { handleProfileOverviewRequest } from "./controllers/profile-overview-controller.js";
import { handleUserAdminRequest } from "./controllers/user-admin-controller.js";
import { handleQrLoginRequest } from "./controllers/qr-login-controller.js";
import { handleAdminNotificationRequest } from "./controllers/admin-notification-controller.js";
import { handleIdentityAccountRequest } from "./controllers/identity-account-controller.js";
import { handleWechatAuthRequest } from "./controllers/wechat-auth-controller.js";
import { handleIdentityProfileRequest } from "./controllers/identity-profile-controller.js";
import { handleRecitationRequest } from "./controllers/recitation-controller.js";
import { handleOssSmokeTestRequest } from "./controllers/oss-smoke-test-controller.js";
import { handleRecitationWorkerRequest } from "./controllers/recitation-worker-controller.js";
import type { IdentityUser } from "./interfaces/identity-repository.js";
import { D1IdentityMapper } from "./mappers/d1-identity-mapper.js";
import { ContentManagedBlogCountMapper } from "./mappers/content-managed-blog-count-mapper.js";
import { D1FeatureModelConfigMapper } from "./mappers/d1-feature-model-config-mapper.js";
import { D1ModelProviderMapper } from "./mappers/d1-model-provider-mapper.js";
import { D1ProfileOverviewMapper } from "./mappers/d1-profile-overview-mapper.js";
import { D1UserAdminMapper } from "./mappers/d1-user-admin-mapper.js";
import { D1BillingMapper } from "./mappers/d1-billing-mapper.js";
import { WechatCodeExchangeMapper } from "./mappers/wechat-code-exchange-mapper.js";
import { RedisQrLoginMapper } from "./mappers/redis-qr-login-mapper.js";
import { WechatLoginQrMapper } from "./mappers/wechat-login-qr-mapper.js";
import { RedisVerificationCodeMapper } from "./mappers/redis-verification-code-mapper.js";
import { PlatformIntegrationMailMapper } from "./mappers/platform-integration-mail-mapper.js";
import { RedisWechatPendingLoginMapper } from "./mappers/redis-wechat-pending-login-mapper.js";
import { ContentNotificationBroadcastMapper } from "./mappers/content-notification-broadcast-mapper.js";
import { D1VirtualPaySettingsMapper } from "./mappers/d1-virtual-pay-settings-mapper.js";
import { D1RecitationMapper } from "./mappers/d1-recitation-mapper.js";
import { OssRecitationAudioMapper } from "./mappers/oss-recitation-audio-mapper.js";
import { DashScopeRecitationAsrMapper } from "./mappers/dashscope-recitation-asr-mapper.js";
import { D1AgentRecitationMapper } from "./mappers/d1-agent-recitation-mapper.js";
import { D1PaymentEntitlementMapper } from "./mappers/d1-payment-entitlement-mapper.js";
import { IdentityAuthService } from "./services/identity-auth-service.js";
import { IdentityAccountService } from "./services/identity-account-service.js";
import { IdentityProfileService } from "./services/identity-profile-service.js";
import { IdentitySessionService } from "./services/identity-session-service.js";
import { ProfileOverviewService } from "./services/profile-overview-service.js";
import { ModelProviderService } from "./services/model-provider-service.js";
import { FeatureModelConfigService } from "./services/feature-model-config-service.js";
import { WechatCredentialService } from "./services/wechat-credential-service.js";
import { VirtualPaySettingsService } from "./services/virtual-pay-settings-service.js";
import { UserAdminService } from "./services/user-admin-service.js";
import { BillingService } from "./services/billing-service.js";
import { QrLoginService } from "./services/qr-login-service.js";
import { WechatLoginService } from "./services/wechat-login-service.js";
import { WechatAccountBindingService } from "./services/wechat-account-binding-service.js";
import { VerificationCodeService } from "./services/verification-code-service.js";
import { NotificationDiagnosticService } from "./services/notification-diagnostic-service.js";
import { RecitationService } from "./services/recitation-service.js";
import { RecitationAudioService } from "./services/recitation-audio-service.js";
import { RecitationAsrWorkerService } from "./services/recitation-asr-worker-service.js";
import { resolveFeatureModelGateway } from "./services/model-gateway-service.js";
import { BlogAgentModelService } from "./services/blog-agent-model-service.js";
import { FeatureBlogAgentModelCandidateMapper } from "./mappers/feature-blog-agent-model-candidate-mapper.js";
import { CoreBlogAgentModelMapper } from "./mappers/core-blog-agent-model-mapper.js";
import { AgentRecitationService } from "./services/agent-recitation-service.js";
import { PaymentEntitlementService } from "./services/payment-entitlement-service.js";
import { WechatPaymentIdentityService } from "./services/wechat-payment-identity-service.js";
import { grantRegistrationPoints, handleBillingRequest } from "./billing.js";
import { hashPassword } from "./password.js";
import {
  consumeFixedWindow,
  hasRedisCredentials,
} from "./redis.js";

interface CoreEnvironment extends ServiceEnvironment {
  DB: D1Binding;
  JWT_SECRET?: string;
  AUTH_PASSWORD_PEPPER?: string;
  JWT_ACCESS_TTL_SECONDS?: string;
  JWT_REFRESH_TTL_SECONDS?: string;
  AUTH_REFRESH_PROTOCOL_ENABLED?: string;
  AUTH_REFRESH_ENCRYPTION_KEY?: string;
  AUTH_REFRESH_SESSION_TTL_SECONDS?: string;
  AUTH_REFRESH_GRACE_SECONDS?: string;
  AUTH_REFRESH_RETENTION_HOURS?: string;
  UPSTASH_REDIS_REST_URL?: string;
  UPSTASH_REDIS_REST_TOKEN?: string;
  PLATFORM_INTEGRATION_SERVICE_URL?: string;
  PLATFORM_INTEGRATION_SERVICE_TOKEN?: string;
  PLATFORM_INTEGRATION_SERVICE?: WorkerServiceBinding;
  CONTENT_AUTOMATION_SERVICE_URL?: string;
  CONTENT_AUTOMATION_SERVICE_TOKEN?: string;
  CONTENT_AUTOMATION_SERVICE?: WorkerServiceBinding;
  WECHAT_MINIAPP_APP_ID?: string;
  WECHAT_MINIAPP_APP_SECRET?: string;
  WECHAT_MINIAPP_LOGIN_PAGE?: string;
  WECHAT_MINIAPP_ENV_VERSION?: string;
  WECHAT_MINIAPP_CODE2SESSION_URL?: string;
  WECHAT_CREDENTIAL_ENCRYPTION_KEY?: string;
  MODEL_PROVIDER_ENCRYPTION_KEY?: string;
  ADMIN_MODEL_GATEWAY_ENABLED?: string;
  BLOG_AGENT_WEB_SEARCH_ENABLED?: string;
  RECITATION_ENABLED?: string;
  RECITATION_MAX_AUDIO_BYTES?: string;
  RECITATION_WORKER_BATCH_SIZE?: string;
  RECITATION_STALE_CLAIM_SECONDS?: string;
  RECITATION_OSS_OBJECT_PREFIX?: string;
  ALIYUN_OSS_ENDPOINT?: string;
  ALIYUN_OSS_BUCKET?: string;
  ALIYUN_OSS_LEGACY_BUCKET?: string;
  ALIYUN_OSS_REGION?: string;
  ALIYUN_OSS_ACCESS_KEY_ID?: string;
  ALIYUN_OSS_ACCESS_KEY_SECRET?: string;
}

type UserRow = IdentityUser;

const DEFAULT_JWT_TTL_SECONDS = 604800;

function success(data: unknown): Response {
  return jsonResponse({ code: 200, message: "success", data });
}

function configuredPositiveInteger(value: string | undefined, fallback: number): number {
  if (!value || !/^\d+$/.test(value)) return fallback;
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : fallback;
}

function refreshSettings(env: CoreEnvironment) {
  return {
    jwtSecret: env.JWT_SECRET,
    refreshProtocolEnabled: env.AUTH_REFRESH_PROTOCOL_ENABLED,
    refreshEncryptionKey: env.AUTH_REFRESH_ENCRYPTION_KEY,
    accessTtlSeconds: env.JWT_ACCESS_TTL_SECONDS,
    refreshTtlSeconds: env.JWT_REFRESH_TTL_SECONDS,
    refreshSessionTtlSeconds: env.AUTH_REFRESH_SESSION_TTL_SECONDS,
    refreshGraceSeconds: env.AUTH_REFRESH_GRACE_SECONDS,
    refreshRetentionHours: env.AUTH_REFRESH_RETENTION_HOURS,
  };
}

function requestIp(request: Request): string {
  return request.headers.get("x-dinqor-client-ip")?.trim().slice(0, 128) || "unknown";
}

async function enforceAuthLimits(
  request: Request,
  env: CoreEnvironment,
  rules: Array<{ namespace: string; identifier: string; limit: number }>,
): Promise<Response | null> {
  if (!hasRedisCredentials(env)) return apiError(503, "身份服务限流尚未配置");
  for (const rule of rules) {
    const decision = await consumeFixedWindow(env, rule.namespace, rule.identifier, rule.limit, 60);
    if (!decision.allowed) {
      const retryAfter = Math.max(1, Math.ceil((decision.resetAt - Date.now()) / 1000));
      const limited = apiError(429, "操作过于频繁，请稍后再试");
      limited.headers.set("retry-after", String(retryAfter));
      return limited;
    }
  }
  return null;
}

async function createTokenResponse(
  user: UserRow,
  env: CoreEnvironment,
  sessions: IdentitySessionService,
): Promise<Response> {
  if (!env.JWT_SECRET || new TextEncoder().encode(env.JWT_SECRET).byteLength < 32) {
    return apiError(503, "身份服务尚未配置");
  }
  const stateful = env.AUTH_REFRESH_PROTOCOL_ENABLED === "true";
  let issued: Awaited<ReturnType<IdentitySessionService["issueRefreshSession"]>> = null;
  try {
    issued = await sessions.issueRefreshSession(user.id, refreshSettings(env));
  } catch {
    return apiError(503, "身份服务尚未配置");
  }
  const accessTtlSeconds = configuredPositiveInteger(env.JWT_ACCESS_TTL_SECONDS,
    stateful ? 1800 : DEFAULT_JWT_TTL_SECONDS);
  const refreshTtlSeconds = configuredPositiveInteger(
    env.JWT_REFRESH_TTL_SECONDS,
    DEFAULT_JWT_TTL_SECONDS,
  );
  const version = user.authVersion ?? 0;
  const accessToken = await signJwtToken(
    String(user.id), env.JWT_SECRET,
    { role: user.role, ver: version, type: "access", ...(issued ? { sid: issued.sid } : {}) },
    accessTtlSeconds,
  );
  const refreshToken = issued?.refreshToken ?? await signJwtToken(
    String(user.id), env.JWT_SECRET, { ver: version, type: "refresh" }, refreshTtlSeconds,
  );
  return success({
    accessToken,
    refreshToken,
    tokenType: "Bearer",
    expiresIn: accessTtlSeconds,
    userInfo: userInfo(user),
    needsEmailBinding: !user.email,
    pendingBind: false,
    pendingBindToken: null,
  });
}

async function handleCoreRequest(
  request: Request,
  env: CoreEnvironment,
): Promise<Response | null> {
  try {
    const url = new URL(request.url);
    const identityMapper = new D1IdentityMapper(env.DB);
    const billingService = new BillingService(new D1BillingMapper(env.DB));
    const identityAuth = new IdentityAuthService(identityMapper);
    const identityAccounts = new IdentityAccountService(identityMapper, identityMapper);
    const wechatAccountBinding = new WechatAccountBindingService(identityAccounts);
    const identityProfiles = new IdentityProfileService(identityMapper);
    const identitySessions = new IdentitySessionService(identityMapper);
    const wechatCredentials = new WechatCredentialService(identityMapper);
    const wechatCodeExchange = new WechatCodeExchangeMapper();
    const wechatLoginService = new WechatLoginService(
      wechatCredentials,
      wechatCodeExchange,
      new RedisWechatPendingLoginMapper(env),
    );
    const paymentIdentity = new WechatPaymentIdentityService(
      identityMapper, wechatCredentials, wechatCodeExchange,
    );
    const paymentEntitlements = new PaymentEntitlementService(new D1PaymentEntitlementMapper(env.DB));
    const qrLoginService = new QrLoginService(new RedisQrLoginMapper(env));
    const wechatLoginQr = new WechatLoginQrMapper(env, wechatCredentials);
    const verificationCodes = new VerificationCodeService(
      new RedisVerificationCodeMapper(env),
      new PlatformIntegrationMailMapper(env),
    );
    const notificationDiagnostics = new NotificationDiagnosticService(
      new ContentNotificationBroadcastMapper(env),
    );
    const profileOverview = new ProfileOverviewService(
      new D1ProfileOverviewMapper(env.DB),
      new ContentManagedBlogCountMapper(env),
    );
    const modelProviders = new ModelProviderService(new D1ModelProviderMapper(env.DB));
    const featureModelConfigs = new FeatureModelConfigService(new D1FeatureModelConfigMapper(env.DB));
    const virtualPaySettings = new VirtualPaySettingsService(new D1VirtualPaySettingsMapper(env.DB));
    const recitationMapper = new D1RecitationMapper(env.DB);
    const recitationAudioStorage = new OssRecitationAudioMapper(env);
    const recitation = new RecitationService(recitationMapper, recitationAudioStorage);
    const recitationAudio = new RecitationAudioService(
      recitationMapper,
      recitationAudioStorage,
      env.RECITATION_ENABLED === "true",
      configuredPositiveInteger(env.RECITATION_MAX_AUDIO_BYTES, 20 * 1024 * 1024),
    );
    const asr = new DashScopeRecitationAsrMapper({
      async candidates() {
        const result = await resolveFeatureModelGateway(env, "recitation_asr");
        return result.candidates.filter((candidate) => candidate.apiStyle === "DASHSCOPE_ASR")
          .map(({ providerId, baseUrl, apiKey, model }) => ({ providerId, baseUrl, apiKey, model }));
      },
    });
    const recitationAsrWorker = new RecitationAsrWorkerService(
      recitationMapper, recitationAudioStorage, asr, env.RECITATION_ENABLED === "true",
      configuredPositiveInteger(env.RECITATION_WORKER_BATCH_SIZE, 10),
      configuredPositiveInteger(env.RECITATION_STALE_CLAIM_SECONDS, 120),
    );
    const agentRecitation = new AgentRecitationService(
      recitationMapper,
      new D1AgentRecitationMapper(env.DB),
      recitation,
    );
    const sessionSettings = refreshSettings(env);
    const userAdmin = new UserAdminService(new D1UserAdminMapper(env.DB));
    const internalAuthResolve = await handleInternalAgentAuthResolve(request, env, identityAuth);
    if (internalAuthResolve) return internalAuthResolve;
    const internalAgentRecitation = await handleAgentRecitationRequest(request, agentRecitation);
    if (internalAgentRecitation) return internalAgentRecitation;
    const internalAgentModelConfig = await handleInternalAgentModelConfig(request, env);
    if (internalAgentModelConfig) return internalAgentModelConfig;
    const blogAgentModel = new BlogAgentModelService(
      new CoreBlogAgentModelMapper(new FeatureBlogAgentModelCandidateMapper(env)),
      env.BLOG_AGENT_WEB_SEARCH_ENABLED === "true",
    );
    const blogAgentModelResponse = await handleBlogAgentModelRequest(request, blogAgentModel);
    if (blogAgentModelResponse) return blogAgentModelResponse;
    const internalPayment = await handleInternalPaymentRequest(request, {
      environment: env,
      credentials: wechatCredentials,
      paymentIdentity,
      entitlements: paymentEntitlements,
    });
    if (internalPayment) return internalPayment;
    const qrLogin = await handleQrLoginRequest(request, {
      service: qrLoginService,
      qrImages: wechatLoginQr,
      sessions: identitySessions,
      resolveUser: (authRequest) => resolveCurrentUser(authRequest, env, identityAuth),
      createTokenResponse: (user) => createTokenResponse(user, env, identitySessions),
      enforceRateLimit: (limitedRequest, namespace, limit) => enforceAuthLimits(
        limitedRequest,
        env,
        [{ namespace, identifier: requestIp(limitedRequest), limit }],
      ),
    });
    if (qrLogin) return qrLogin;
    const identityAccountResponse = await handleIdentityAccountRequest(request, {
      accounts: identityAccounts,
      verificationCodes,
      passwordPepper: env.AUTH_PASSWORD_PEPPER,
      enforceLimits: (limitedRequest, rules) => enforceAuthLimits(limitedRequest, env, rules),
      resolveUser: (authRequest) => resolveCurrentUser(authRequest, env, identityAuth),
      createTokenResponse: (user) => createTokenResponse(user, env, identitySessions),
      grantRegistrationPoints: (userId) => grantRegistrationPoints(env, billingService, userId),
    });
    if (identityAccountResponse) return identityAccountResponse;
    const wechatAuthResponse = await handleWechatAuthRequest(request, {
      environment: env,
      accounts: identityAccounts,
      wechatLogin: wechatLoginService,
      accountBinding: wechatAccountBinding,
      verificationCodes,
      enforceLimits: (limitedRequest, rules) => enforceAuthLimits(limitedRequest, env, rules),
      resolveUser: (authRequest) => resolveCurrentUser(authRequest, env, identityAuth),
      createTokenResponse: (user) => createTokenResponse(user, env, identitySessions),
      grantRegistrationPoints: (userId) => grantRegistrationPoints(env, billingService, userId),
    });
    if (wechatAuthResponse) return wechatAuthResponse;
    const identityProfile = await handleIdentityProfileRequest(
      request,
      identityProfiles,
      (authRequest) => resolveCurrentUser(authRequest, env, identityAuth),
    );
    if (identityProfile) return identityProfile;
    const profileOverviewResponse = await handleProfileOverviewRequest(
      request, env, identityAuth, profileOverview,
    );
    if (profileOverviewResponse) return profileOverviewResponse;
    const identityAuthResponse = await handleIdentityAuthRequest(request, env, identityAuth);
    if (identityAuthResponse) return identityAuthResponse;
    const sessionResponse = await handleIdentitySessionRequest(
      request,
      sessionSettings,
      identitySessions,
      async (refreshRequest) => {
        if (!hasRedisCredentials(env)) return null;
        const identity = refreshRequest.headers.get("x-dinqor-client-ip")?.trim() || "unknown";
        const decision = await consumeFixedWindow(env, "refresh", identity, 30, 60);
        if (decision.allowed) return null;
        const retryAfter = Math.max(1, Math.ceil((decision.resetAt - Date.now()) / 1000));
        const limited = apiError(429, "刷新请求过于频繁，请稍后再试");
        limited.headers.set("retry-after", String(retryAfter));
        return limited;
      },
    );
    if (sessionResponse) return sessionResponse;
    const recitationWorkerResponse = await handleRecitationWorkerRequest(request, recitationAsrWorker);
    if (recitationWorkerResponse) return recitationWorkerResponse;
    const recitationResponse = await handleRecitationRequest(request, env, identityAuth, recitation, recitationAudio);
    if (recitationResponse) return recitationResponse;
    const userAdminResponse = await handleUserAdminRequest(request, env, identityAuth, userAdmin);
    if (userAdminResponse) return userAdminResponse;
    const broadcast = await handleAdminNotificationRequest(
      request,
      notificationDiagnostics,
      (authRequest) => resolveCurrentUser(authRequest, env, identityAuth),
    );
    if (broadcast) return broadcast;
    const adminSettings = await handleAdminSettingsRequest(request, {
      ...env,
      resolveUser: (authRequest) => resolveCurrentUser(authRequest, env, identityAuth),
    }, modelProviders, featureModelConfigs, wechatCredentials, virtualPaySettings);
    if (adminSettings) return adminSettings;
    const billing = await handleBillingRequest(
      request, env, billingService, (authRequest) => resolveCurrentUser(authRequest, env, identityAuth),
    );
    if (billing) return billing;
    return null;
  } catch {
    return apiError(503, "身份服务暂不可用");
  }
}

const coreService = createBusinessService<CoreEnvironment>("core", handleCoreRequest);

export default {
  ...coreService,
  async fetch(
    request: Request,
    env: CoreEnvironment,
    context: { waitUntil(task: Promise<unknown>): void },
  ): Promise<Response> {
    const ossSmokeTest = await handleOssSmokeTestRequest(request, env);
    if (ossSmokeTest) return ossSmokeTest;
    return coreService.fetch(request, env, context);
  },
  async scheduled(
    _controller: unknown,
    env: CoreEnvironment,
    context?: { waitUntil(task: Promise<unknown>): void },
  ): Promise<void> {
    const sessions = new IdentitySessionService(new D1IdentityMapper(env.DB));
    const recitationRepository = new D1RecitationMapper(env.DB);
    const storage = new OssRecitationAudioMapper(env);
    const asr = new DashScopeRecitationAsrMapper({
      async candidates() {
        const result = await resolveFeatureModelGateway(env, "recitation_asr");
        return result.candidates.filter((candidate) => candidate.apiStyle === "DASHSCOPE_ASR")
          .map(({ providerId, baseUrl, apiKey, model }) => ({ providerId, baseUrl, apiKey, model }));
      },
    });
    const worker = new RecitationAsrWorkerService(
      recitationRepository, storage, asr, env.RECITATION_ENABLED === "true",
      configuredPositiveInteger(env.RECITATION_WORKER_BATCH_SIZE, 10),
      configuredPositiveInteger(env.RECITATION_STALE_CLAIM_SECONDS, 120),
    );
    const now = Date.now();
    const work = Promise.all([
      sessions.cleanupExpiredRevocations(now),
      sessions.cleanupRefreshData(now),
      worker.process(),
    ]);
    if (context) context.waitUntil(work);
    else await work;
  },
};
