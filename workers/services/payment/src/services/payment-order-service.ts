import { PaymentCoreError, type PaymentCorePort } from "../interfaces/payment-core-port.js";
import type { PaymentOrderRecord, PaymentOrderRepository } from "../interfaces/payment-order-repository.js";
import type { PaymentEnvironment } from "../interfaces/payment-environment.js";
import type { WechatMessagePort } from "../interfaces/wechat-message-port.js";
import type { WechatDeliveryNotification, WechatPaymentPort } from "../interfaces/wechat-payment-port.js";
import type { PaymentSettingsService } from "./payment-settings-service.js";

import { paymentExpiresAt, paymentStatus } from "./payment-order-lifetime.js";

const encoder = new TextEncoder();
const PAYMENT_MODE = "short_series_goods";

export class PaymentRequestError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
    this.name = "PaymentRequestError";
  }
}

async function hmacSha256Hex(secret: string, value: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    "raw",
    encoder.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign("HMAC", key, encoder.encode(value));
  return Array.from(new Uint8Array(signature), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

function outTradeNo(): string {
  const random = crypto.getRandomValues(new Uint8Array(5));
  const hex = Array.from(random, (byte) => byte.toString(16).padStart(2, "0"))
    .join("")
    .toUpperCase();
  return `D${Date.now().toString(36).toUpperCase()}${hex}`;
}

function statusView(order: PaymentOrderRecord) {
  return {
    outTradeNo: order.outTradeNo,
    status: paymentStatus(order),
    expiresAt: paymentExpiresAt(order),
    serverNow: new Date().toISOString(),
    tier: order.tier,
    deliveredAt: order.deliveredAt,
  };
}

export class PaymentOrderService {
  constructor(
    private readonly orders: PaymentOrderRepository,
    private readonly settings: PaymentSettingsService,
    private readonly core: PaymentCorePort,
    private readonly wechat: WechatPaymentPort,
    private readonly messages: WechatMessagePort,
    private readonly env: PaymentEnvironment,
  ) {}

  async createOrder(userId: number, productId: string, loginCode: string, quantity = 1, requestedTradeNo?: string) {
    const settings = await this.settings.resolve(this.env.PAYMENT_SETTINGS_ENCRYPTION_KEY);
    if (!settings) throw new PaymentRequestError(400, "微信虚拟支付尚未在管理台启用");
    const product = settings.products.find((item) => item.productId === productId);
    if (!product) throw new PaymentRequestError(400, "订阅商品不存在或未启用");

    const pointsPurchase = product.kind === "points";
    if (
      !Number.isSafeInteger(quantity) ||
      quantity < 1 ||
      quantity > 1000 ||
      (pointsPurchase && (product.points ?? 0) * quantity > 10000) ||
      (!pointsPurchase && quantity !== 1)
    )
      throw new PaymentRequestError(400, "购买数量无效");
    let reusable = requestedTradeNo
      ? await this.orders.findOwnedOrder(userId, requestedTradeNo)
      : await this.orders.findPayable(userId, productId, quantity);
    if (requestedTradeNo && !reusable) throw new PaymentRequestError(404, "支付订单不存在");
    if (reusable) {
      if (reusable.productId !== productId || reusable.quantity !== quantity)
        throw new PaymentRequestError(409, "支付订单商品不匹配");
      if (paymentStatus(reusable) !== "PENDING")
        throw new PaymentRequestError(409, "订单已支付或已过期，请重新选择商品");
      // Query before signing again so a cancelled client cannot repay an already paid order.
      const verified = await this.reconcile(userId, reusable.outTradeNo);
      if (verified.status !== "PENDING")
        throw new PaymentRequestError(409, "订单已支付或已过期，请重新选择商品");
    }
    let subscription;
    try {
      subscription = await this.core.subscription(userId);
    } catch {
      throw new PaymentRequestError(503, "订阅状态暂不可用");
    }
    const rank = ["FREE", "PLUS", "PRO", "ULTRA"];
    if (pointsPurchase && !subscription.active)
      throw new PaymentRequestError(403, "仅有效订阅用户可购买积分");
    if (
      !pointsPurchase &&
      subscription.active &&
      rank.indexOf(product.tier) <= rank.indexOf(subscription.tier)
    )
      throw new PaymentRequestError(409, "当前套餐有效期内只能升级");
    let identity: { openid: string; sessionKey: string };
    try {
      identity = await this.core.resolveWechatIdentity(userId, loginCode);
    } catch (error) {
      if (error instanceof PaymentCoreError) throw new PaymentRequestError(error.status, error.message);
      throw new PaymentRequestError(503, "微信支付身份服务暂不可用");
    }

    if (!reusable) {
      const tradeNo = outTradeNo();
      try {
        // One atomic INSERT ... SELECT decides which concurrent request owns this checkout.
        const inserted = await this.orders.insertPending({
          outTradeNo: tradeNo, userId, openid: identity.openid,
          productId: product.productId, tier: product.tier, goodsPrice: product.goodsPrice,
          quantity, kind: product.kind ?? "subscription",
          points: (product.points ?? 0) * quantity, durationDays: product.durationDays,
        });
        reusable = inserted ? await this.orders.findOwnedOrder(userId, tradeNo)
          : await this.orders.findPayable(userId, productId, quantity);
      } catch {
        throw new PaymentRequestError(503, "创建虚拟支付订单失败");
      }
    }
    if (!reusable || paymentStatus(reusable) !== "PENDING")
      throw new PaymentRequestError(409, "订单已支付或已过期，请重新选择商品");
    if (reusable.openid !== identity.openid)
      throw new PaymentRequestError(409, "微信支付身份与订单不一致");
    const signData = JSON.stringify({
      offerId: settings.offerId, buyQuantity: reusable.quantity, env: 0, currencyType: "CNY",
      productId: reusable.productId, goodsPrice: reusable.goodsPrice,
      outTradeNo: reusable.outTradeNo, attach: reusable.outTradeNo,
    });
    const [paySig, signature] = await Promise.all([
      hmacSha256Hex(settings.appKey, `requestVirtualPayment&${signData}`),
      hmacSha256Hex(identity.sessionKey, signData),
    ]);
    return { outTradeNo: reusable.outTradeNo, mode: PAYMENT_MODE, signData, paySig, signature,
      expiresAt: paymentExpiresAt(reusable), serverNow: new Date().toISOString() };
  }

  async status(userId: number, tradeNo: string) {
    const order = await this.orders.findOwnedOrder(userId, tradeNo);
    if (!order) throw new PaymentRequestError(404, "支付订单不存在");
    return statusView(order);
  }

  async reconcile(userId: number, tradeNo: string) {
    let order = await this.orders.findOwnedOrder(userId, tradeNo);
    if (!order) throw new PaymentRequestError(404, "支付订单不存在");
    if (order.status !== "PENDING") return statusView(order);

    const settings = await this.settings.resolve(this.env.PAYMENT_SETTINGS_ENCRYPTION_KEY);
    if (!settings) throw new PaymentRequestError(503, "微信虚拟支付尚未配置");
    let result;
    try {
      result = await this.wechat.queryOrder(order.openid, order.outTradeNo, settings.appKey);
    } catch {
      throw new PaymentRequestError(503, "微信虚拟支付查单暂不可用");
    }
    if (result.paid) {
      if (result.paidFee !== order.goodsPrice * order.quantity) {
        throw new PaymentRequestError(409, "微信订单实付金额与本地订单不一致");
      }
      if (!result.wxOrderId) throw new PaymentRequestError(503, "微信支付订单信息不完整");
      await this.deliverOnce(order, result.wxOrderId, result.paidTime);
      order = await this.orders.findOwnedOrder(userId, tradeNo);
      if (!order) throw new PaymentRequestError(404, "支付订单不存在");
    }
    return statusView(order);
  }

  async verifyNotificationSignature(
    signature: string | null,
    timestamp: string | null,
    nonce: string | null,
  ) {
    return this.messages.verifySignature(
      signature,
      timestamp,
      nonce,
      this.env.WECHAT_VIRTUAL_PAY_MESSAGE_TOKEN,
    );
  }

  async deliverNotification(xml: string): Promise<void> {
    let notification: WechatDeliveryNotification;
    try {
      notification = this.messages.parseDeliveryNotification(xml);
    } catch {
      throw new PaymentRequestError(400, "微信发货通知格式无效");
    }
    if (
      notification.event !== "xpay_goods_deliver_notify" ||
      notification.env !== 0 ||
      !notification.outTradeNo ||
      !notification.openid ||
      !notification.productId ||
      !notification.wxOrderId ||
      !Number.isSafeInteger(notification.quantity) ||
      notification.quantity <= 0
    ) {
      throw new PaymentRequestError(400, "微信发货通知字段无效");
    }
    const order = await this.orders.findOrder(notification.outTradeNo);
    if (
      !order ||
      order.openid !== notification.openid ||
      order.productId !== notification.productId ||
      order.quantity !== notification.quantity
    ) {
      throw new PaymentRequestError(400, "微信发货通知与本地订单不一致");
    }
    if (order.wxOrderId && order.wxOrderId !== notification.wxOrderId) {
      throw new PaymentRequestError(409, "微信支付订单号与本地订单不一致");
    }
    await this.deliverOnce(order, notification.wxOrderId);
  }

  private async deliverOnce(order: PaymentOrderRecord, wxOrderId: string, paidTime?: number) {
    if (order.status === "DELIVERED") return;
    if (order.status !== "PENDING") throw new PaymentRequestError(409, "订单当前状态不允许发货");
    try {
      if (order.kind === "points")
        await this.core.grantPoints({
          userId: order.userId,
          points: order.points ?? 0,
          eventKey: `virtualpay:${order.outTradeNo}`,
        });
      else
        await this.core.grantEntitlement({
          userId: order.userId,
          tier: order.tier,
          durationDays: order.durationDays,
          eventKey: `virtualpay:${order.outTradeNo}`,
        });
    } catch (error) {
      if (error instanceof PaymentCoreError) throw new PaymentRequestError(503, "订阅权益发放暂不可用");
      throw new PaymentRequestError(503, "订阅权益发放暂不可用");
    }
    try {
      const updated = await this.orders.markDelivered(order.id, wxOrderId, Number.isSafeInteger(paidTime) && Number(paidTime) > 0 ? new Date(Number(paidTime) * 1000).toISOString() : undefined);
      if (updated) return;
      const latest = await this.orders.findOrder(order.outTradeNo);
      if (latest?.status === "DELIVERED" && latest.wxOrderId === wxOrderId) return;
      throw new Error("payment order state changed");
    } catch {
      throw new PaymentRequestError(503, "支付订单状态更新失败，请稍后重试");
    }
  }
}
