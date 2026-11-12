import { createBusinessService } from "@dinqor/worker-runtime";
import { handlePaymentRequest } from "./controllers/payment-controller.js";
import type { PaymentEnvironment } from "./interfaces/payment-environment.js";
import { CorePaymentMapper } from "./mappers/core-payment-mapper.js";
import { D1PaymentOrderMapper } from "./mappers/d1-payment-order-mapper.js";
import { D1PaymentSettingsMapper } from "./mappers/d1-payment-settings-mapper.js";
import { WechatMessageMapper } from "./mappers/wechat-message-mapper.js";
import { WechatVirtualPayMapper } from "./mappers/wechat-virtual-pay-mapper.js";
import { PaymentOrderService } from "./services/payment-order-service.js";
import { PaymentSettingsService } from "./services/payment-settings-service.js";
import { handleAlipayRequest } from "./controllers/alipay-controller.js";
import { D1AlipayOrderMapper } from "./mappers/d1-alipay-order-mapper.js";
import { AlipaySdkMapper } from "./mappers/alipay-sdk-mapper.js";
import { resolveAlipayConfiguration } from "./services/alipay-configuration.js";
import { AlipayOrderService } from "./services/alipay-order-service.js";

import { D1PaymentRefundMapper } from "./mappers/d1-payment-refund-mapper.js";
import { PaymentRefundService } from "./services/payment-refund-service.js";
const paymentWorker = createBusinessService<PaymentEnvironment>("payment", async (request, env) => {
  const settings = new PaymentSettingsService(new D1PaymentSettingsMapper(env.DB));
  const core = new CorePaymentMapper(env);
  const alipayResponse = await handleAlipayRequest(request, () => {
    const config = resolveAlipayConfiguration(env);
    return config ? new AlipayOrderService(new D1AlipayOrderMapper(env.DB), core,
      new AlipaySdkMapper(config), config) : null;
  }, env.ALIPAY_REFUNDS_ENABLED === "true");
  if (alipayResponse) return alipayResponse;
  const orders = new PaymentOrderService(
    new D1PaymentOrderMapper(env.DB), settings, core, new WechatVirtualPayMapper(env, core),
    new WechatMessageMapper(), env,
  );
  const refunds = new PaymentRefundService(new D1PaymentOrderMapper(env.DB),new D1PaymentRefundMapper(env.DB),
    core,new WechatVirtualPayMapper(env,core),settings,env);
  return handlePaymentRequest(request, env, settings, orders,refunds);
});

// Recovery does not depend on the user keeping the order page open.
export default {
  ...paymentWorker,
  async scheduled(_event: unknown, env: PaymentEnvironment) {
    const core=new CorePaymentMapper(env);
    await new PaymentRefundService(new D1PaymentOrderMapper(env.DB),new D1PaymentRefundMapper(env.DB),core,
      new WechatVirtualPayMapper(env,core),new PaymentSettingsService(new D1PaymentSettingsMapper(env.DB)),env).sweep();
  },
};
