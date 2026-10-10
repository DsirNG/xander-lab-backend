import type { D1Binding, ServiceEnvironment } from "@dinqor/worker-runtime";

export interface ContentEnvironment extends ServiceEnvironment {
  DB: D1Binding;
  CONTENT_OSS_PUBLIC_BASE_URL?: string;
  CONTENT_OSS_OBJECT_PREFIX?: string;
  CONTENT_MEDIA_OSS_OBJECT_PREFIX?: string;
  ALIYUN_OSS_ENDPOINT?: string;
  ALIYUN_OSS_BUCKET?: string;
  ALIYUN_OSS_REGION?: string;
  ALIYUN_OSS_ACCESS_KEY_ID?: string;
  ALIYUN_OSS_ACCESS_KEY_SECRET?: string;
  PLATFORM_INTEGRATION_SERVICE_URL?: string;
  PLATFORM_INTEGRATION_SERVICE_TOKEN?: string;
  MAIL_FROM?: string;
  EMAIL_REMINDER_MINIMUM_LEAD_SECONDS?: string;
  EMAIL_REMINDER_DISPLAY_ZONE?: string;
  CORE_SERVICE_URL?: string;
  CORE_SERVICE_TOKEN?: string;
}
