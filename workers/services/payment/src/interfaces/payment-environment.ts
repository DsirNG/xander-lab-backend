import type { D1Binding, ServiceEnvironment } from "@dinqor/worker-runtime";

export interface PaymentEnvironment extends ServiceEnvironment {
  DB: D1Binding;
  PAYMENT_SETTINGS_ENCRYPTION_KEY?: string;
  CORE_SERVICE_URL?: string;
  CORE_SERVICE_TOKEN?: string;
  CORE_SERVICE?: { fetch(request: Request): Promise<Response> };
  WECHAT_VIRTUAL_PAY_MESSAGE_TOKEN?: string;
  WECHAT_VIRTUAL_PAY_API_BASE_URL?: string;
  ALIPAY_ENABLED?: string;
  ALIPAY_ENVIRONMENT?: string;
  ALIPAY_APP_ID?: string;
  ALIPAY_SELLER_ID?: string;
  ALIPAY_PRIVATE_KEY?: string;
  ALIPAY_PUBLIC_KEY?: string;
  ALIPAY_RETURN_URL?: string;
  ALIPAY_NOTIFY_URL?: string;
  ALIPAY_PRODUCTS?: string;
  ALIPAY_REFUNDS_ENABLED?: string;
}
