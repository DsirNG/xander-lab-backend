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

export default createBusinessService<PaymentEnvironment>("payment", async (request, env) => {
  const settings = new PaymentSettingsService(new D1PaymentSettingsMapper(env.DB));
  const core = new CorePaymentMapper(env);
  const orders = new PaymentOrderService(
    new D1PaymentOrderMapper(env.DB), settings, core, new WechatVirtualPayMapper(env, core),
    new WechatMessageMapper(), env,
  );
  return handlePaymentRequest(request, env, settings, orders);
});
