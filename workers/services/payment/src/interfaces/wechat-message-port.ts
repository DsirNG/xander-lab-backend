import type { WechatDeliveryNotification } from "./wechat-payment-port.js";

export interface WechatMessagePort {
  verifySignature(signature: string | null, timestamp: string | null, nonce: string | null, token: string | undefined): Promise<boolean>;
  parseDeliveryNotification(xml: string): WechatDeliveryNotification;
}
