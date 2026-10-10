import type { D1Binding, ServiceEnvironment } from "@dinqor/worker-runtime";

export interface PaymentEnvironment extends ServiceEnvironment {
  DB: D1Binding;
  PAYMENT_SETTINGS_ENCRYPTION_KEY?: string;
  CORE_SERVICE_URL?: string;
  CORE_SERVICE_TOKEN?: string;
  CORE_SERVICE?: { fetch(request: Request): Promise<Response> };
  WECHAT_VIRTUAL_PAY_MESSAGE_TOKEN?: string;
  WECHAT_VIRTUAL_PAY_API_BASE_URL?: string;
}
