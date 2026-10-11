import type { D1Binding, ServiceEnvironment, WorkerServiceBinding } from "@dinqor/worker-runtime";
import type { McpOAuthEnvironment } from "./mcp-oauth-environment.js";

export interface PlatformEnvironment extends ServiceEnvironment, McpOAuthEnvironment {
  DB: D1Binding;
  PLATFORM_CREDENTIAL_ENCRYPTION_KEY?: string;
  PLATFORM_SELENIUM_URL?: string;
  PLATFORM_CSDN_AUTHORIZATION_TIMEOUT_SECONDS?: string;
  PLATFORM_JUEJIN_AUTHORIZATION_TIMEOUT_SECONDS?: string;
  CONTENT_AUTOMATION_SERVICE_URL?: string;
  CONTENT_AUTOMATION_SERVICE_TOKEN?: string;
  CONTENT_AUTOMATION_SERVICE?: WorkerServiceBinding;
  CORE_SERVICE_URL?: string;
  CORE_SERVICE_TOKEN?: string;
  CORE_SERVICE?: WorkerServiceBinding;
  MCP_BLOG_ENABLED?: string;
  MCP_CSDN_ENABLED?: string;
  MCP_DUAL_ENABLED?: string;
  MCP_JUEJIN_ENABLED?: string;
  PLATFORM_CSDN_API_KEY?: string;
  PLATFORM_CSDN_WEB_CONSOLE_SECRET?: string;
  PLATFORM_OSS_PREFIX?: string;
  PLATFORM_FILE_ACCESS_URL?: string;
  ALIYUN_OSS_ACCESS_KEY_ID?: string;
  ALIYUN_OSS_ACCESS_KEY_SECRET?: string;
  ALIYUN_OSS_ENDPOINT?: string;
  ALIYUN_OSS_REGION?: string;
  ALIYUN_OSS_BUCKET?: string;
  ALIYUN_OSS_LEGACY_BUCKET?: string;
  JUEJIN_API_BASE_URL?: string;
  JUEJIN_DEFAULT_AID?: string;
  JUEJIN_CATALOG_FRESHNESS_HOURS?: string;
  RESEND_API_KEY?: string;
  MAIL_FROM?: string;
}
