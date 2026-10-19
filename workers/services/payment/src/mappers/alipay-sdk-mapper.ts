import { AlipaySdk } from "alipay-sdk";
import { AlipayOperationError } from "../interfaces/alipay-port.js";
import type { AlipayConfiguration, AlipayPort } from "../interfaces/alipay-port.js";
import { amountText } from "../services/alipay-configuration.js";

// Workers run in UTC; the gateway requires a Beijing wall-clock timestamp.
const timestamp = () => new Date(Date.now() + 8 * 60 * 60 * 1000).toISOString().slice(0,19).replace("T"," ");

/** Official SDK 4.14 public-key APIs; raw key values never leave this adapter. */
export class AlipaySdkMapper implements AlipayPort {
  private readonly sdk: AlipaySdk;
  constructor(private readonly config: AlipayConfiguration) {
    this.sdk = new AlipaySdk({
      appId: config.appId, privateKey: config.privateKey, alipayPublicKey: config.publicKey,
      keyType: "PKCS1", signType: "RSA2", gateway: config.gateway, timeout: 8000, camelcase: false,
    });
  }

  page(order: { outTradeNo: string; priceCents: number; subject: string }): string {
    return this.sdk.pageExec("alipay.trade.page.pay", "POST", {
      timestamp: timestamp(),
      returnUrl: this.config.returnUrl,
      ...(this.config.notifyUrl ? { notifyUrl: this.config.notifyUrl } : {}),
      bizContent: { out_trade_no: order.outTradeNo, total_amount: amountText(order.priceCents),
        subject: order.subject, product_code: "FAST_INSTANT_TRADE_PAY", timeout_express: "30m" },
    });
  }

  async call(method: string, bizContent: Record<string, string>) {
    const result = await this.sdk.exec(method, { bizContent, timestamp: timestamp() }, { validateSign: true });
    if (result.code !== "10000") throw new AlipayOperationError(result.sub_code === "ACQ.TRADE_NOT_EXIST");
    return result;
  }

  verifyNotification(params: Record<string, string>): boolean {
    return params.sign_type === "RSA2" && this.sdk.checkNotifySignV2(params);
  }
}
