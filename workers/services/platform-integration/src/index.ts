import { createBusinessService } from "@dinqor/worker-runtime";
import { handleCsdnPublicationRequest } from "./controllers/csdn-publication-controller.js";
import { handlePlatformBlogSyncRequest } from "./controllers/platform-blog-sync-controller.js";
import { handlePlatformCredentialRequest } from "./controllers/platform-credential-controller.js";
import { handleMcpOAuthRequest } from "./controllers/mcp-oauth-controller.js";
import { handleMcpTransportRequest } from "./controllers/mcp-transport-controller.js";
import { handlePlatformFileRequest, handlePublicPlatformImageRequest } from "./controllers/platform-file-controller.js";
import { handleJuejinCatalogRequest } from "./controllers/juejin-catalog-controller.js";
import { handleJuejinPublicationRequest } from "./controllers/juejin-publication-controller.js";
import { handleVerificationMailRequest } from "./controllers/verification-mail-controller.js";
import { handleTransactionalMailRequest } from "./controllers/transactional-mail-controller.js";
import type { PlatformEnvironment } from "./interfaces/platform-environment.js";
import { D1PlatformCredentialMapper } from "./mappers/d1-platform-credential-mapper.js";
import { D1PlatformAuthorizationMapper } from "./mappers/d1-platform-authorization-mapper.js";
import { D1McpOAuthMapper } from "./mappers/d1-mcp-oauth-mapper.js";
import { WebDriverPlatformBrowserMapper } from "./mappers/web-driver-platform-browser-mapper.js";
import { ContentMcpMapper, CoreMcpBillingMapper } from "./mappers/content-mcp-mapper.js";
import { ContentBlogPublicationMapper } from "./mappers/content-blog-publication-mapper.js";
import { D1JuejinCatalogMapper } from "./mappers/d1-juejin-catalog-mapper.js";
import { D1PlatformFileMapper } from "./mappers/d1-platform-file-mapper.js";
import { AliyunPlatformFileStorageMapper } from "./mappers/aliyun-platform-file-storage-mapper.js";
import { CsdnPublicationMapper } from "./mappers/csdn-publication-mapper.js";
import { JuejinCatalogMapper } from "./mappers/juejin-catalog-mapper.js";
import { ResendVerificationMailMapper } from "./mappers/resend-verification-mail-mapper.js";
import { ResendTransactionalMailMapper } from "./mappers/resend-transactional-mail-mapper.js";
import { PlatformCredentialService } from "./services/platform-credential-service.js";
import { PlatformBlogSyncService } from "./services/platform-blog-sync-service.js";
import { PlatformAuthorizationService } from "./services/platform-authorization-service.js";
import { McpOAuthService } from "./services/mcp-oauth-service.js";
import { PlatformMcpService } from "./services/platform-mcp-service.js";
import { PlatformFileService } from "./services/platform-file-service.js";
import { JuejinCatalogService } from "./services/juejin-catalog-service.js";
import { JuejinPublicationService } from "./services/juejin-publication-service.js";
import { CsdnPublicationService } from "./services/csdn-publication-service.js";
import { VerificationMailService } from "./services/verification-mail-service.js";
import { TransactionalMailService } from "./services/transactional-mail-service.js";

const platformWorker = createBusinessService<PlatformEnvironment>("platform-integration", async (request, env) => {
  const credentialService = new PlatformCredentialService(
    new D1PlatformCredentialMapper(env.DB), env.PLATFORM_CREDENTIAL_ENCRYPTION_KEY,
  );
  const publicBlogSync = await handlePlatformBlogSyncRequest(
    request,
    new PlatformBlogSyncService(new ContentBlogPublicationMapper(env)),
  );
  if (publicBlogSync) return publicBlogSync;
  const authorizationService = new PlatformAuthorizationService(
    new D1PlatformAuthorizationMapper(env.DB),
    new WebDriverPlatformBrowserMapper(env.PLATFORM_SELENIUM_URL),
    credentialService,
    {
      csdn: Number(env.PLATFORM_CSDN_AUTHORIZATION_TIMEOUT_SECONDS ?? 35),
      juejin: Number(env.PLATFORM_JUEJIN_AUTHORIZATION_TIMEOUT_SECONDS ?? 60),
    },
  );
  const mcpOAuthService = new McpOAuthService(new D1McpOAuthMapper(env.DB), env);
  const contentMcp = new ContentMcpMapper(env);
  const mcpService = new PlatformMcpService(
    contentMcp,
    contentMcp,
    new CoreMcpBillingMapper(env),
    authorizationService,
    credentialService,
    {
      blog: env.MCP_BLOG_ENABLED === "true",
      csdn: env.MCP_CSDN_ENABLED === "true",
      dual: env.MCP_DUAL_ENABLED === "true",
      juejin: env.MCP_JUEJIN_ENABLED === "true",
    },
  );
  const mcpTransport = await handleMcpTransportRequest(request, mcpOAuthService, mcpService);
  if (mcpTransport) return mcpTransport;
  const oauth = await handleMcpOAuthRequest(request, env, mcpOAuthService);
  if (oauth) return oauth;
  const credentials = await handlePlatformCredentialRequest(request, env, credentialService, authorizationService);
  if (credentials) return credentials;

  const fileService = new PlatformFileService(
    new D1PlatformFileMapper(env.DB),
    new AliyunPlatformFileStorageMapper(env),
    { accessUrl: env.PLATFORM_FILE_ACCESS_URL },
  );
  const files = await handlePlatformFileRequest(request, env, fileService);
  if (files) return files;

  const configuredFreshness = Number(env.JUEJIN_CATALOG_FRESHNESS_HOURS ?? 24);
  const catalogService = new JuejinCatalogService(
    credentialService,
    new D1JuejinCatalogMapper(env.DB),
    new JuejinCatalogMapper(env),
    Number.isSafeInteger(configuredFreshness) && configuredFreshness >= 1 && configuredFreshness <= 720
      ? configuredFreshness : 24,
  );
  const catalog = await handleJuejinCatalogRequest(request, catalogService);
  if (catalog) return catalog;

  const publicationService = new JuejinPublicationService(credentialService, catalogService, new JuejinCatalogMapper(env));
  const publication = await handleJuejinPublicationRequest(request, publicationService);
  if (publication) return publication;

  const csdnPublicationService = new CsdnPublicationService(
    credentialService, new CsdnPublicationMapper(env),
  );
  const csdnPublication = await handleCsdnPublicationRequest(request, csdnPublicationService);
  if (csdnPublication) return csdnPublication;

  const mailService = new VerificationMailService(new ResendVerificationMailMapper(env));
  const reminderMail = new TransactionalMailService(new ResendTransactionalMailMapper(env));
  const reminderMailResponse = await handleTransactionalMailRequest(request, env, reminderMail);
  if (reminderMailResponse) return reminderMailResponse;
  return handleVerificationMailRequest(request, mailService);
}, async (request, env) => {
  const fileService = new PlatformFileService(
    new D1PlatformFileMapper(env.DB),
    new AliyunPlatformFileStorageMapper(env),
  );
  return handlePublicPlatformImageRequest(request, fileService);
});

interface ScheduledControllerLike {
  scheduledTime: number;
  cron: string;
  noRetry(): void;
}

interface ScheduledContextLike {
  waitUntil(promise: Promise<unknown>): void;
}

function createAuthorizationService(env: PlatformEnvironment): PlatformAuthorizationService {
  return new PlatformAuthorizationService(
    new D1PlatformAuthorizationMapper(env.DB),
    new WebDriverPlatformBrowserMapper(env.PLATFORM_SELENIUM_URL),
    new PlatformCredentialService(new D1PlatformCredentialMapper(env.DB), env.PLATFORM_CREDENTIAL_ENCRYPTION_KEY),
    {
      csdn: Number(env.PLATFORM_CSDN_AUTHORIZATION_TIMEOUT_SECONDS ?? 35),
      juejin: Number(env.PLATFORM_JUEJIN_AUTHORIZATION_TIMEOUT_SECONDS ?? 60),
    },
  );
}

export default {
  ...platformWorker,
  async scheduled(
    controller: ScheduledControllerLike,
    env: PlatformEnvironment,
    context?: ScheduledContextLike,
  ): Promise<void> {
    const now = new Date(controller.scheduledTime).toISOString();
    const work = Promise.all([
      new D1McpOAuthMapper(env.DB).cleanupExpired(now),
      createAuthorizationService(env).cleanupExpired(),
    ]);
    if (context) context.waitUntil(work);
    else await work;
  },
};
