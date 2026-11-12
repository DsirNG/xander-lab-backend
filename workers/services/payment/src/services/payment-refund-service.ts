import type {
  PaymentOrderRecord,
  PaymentOrderRepository,
} from "../interfaces/payment-order-repository.js";
import type {
  PaymentRefund,
  PaymentRefundRepository,
} from "../interfaces/payment-refund-repository.js";
import type { PaymentCorePort } from "../interfaces/payment-core-port.js";
import type { WechatPaymentPort } from "../interfaces/wechat-payment-port.js";
import type { PaymentSettingsService } from "./payment-settings-service.js";
import type { PaymentEnvironment } from "../interfaces/payment-environment.js";
import { paymentExpiresAt, paymentStatus } from "./payment-order-lifetime.js";
import { PaymentRequestError } from "./payment-order-service.js";

/** All values are integer cents/millipoints; never use wallet-wide balance for refunds. */
export function proportionalRefund(
  paid: number,
  remaining: number,
  original: number,
) {
  if (
    ![paid, remaining, original].every(Number.isSafeInteger) ||
    paid < 0 ||
    remaining < 0 ||
    original <= 0 ||
    remaining > original
  )
    throw new PaymentRequestError(409, "退款积分数据不一致");
  return Number((BigInt(paid) * BigInt(remaining)) / BigInt(original));
}
export const REFUND_WINDOW_SECONDS = 7 * 24 * 60 * 60;
export function refundWindowOpen(
  paidTime: number | undefined,
  now = Date.now(),
) {
  return (
    Number.isSafeInteger(paidTime) &&
    Number(paidTime) > 0 &&
    Number(paidTime) * 1000 <= now &&
    now < (Number(paidTime) + REFUND_WINDOW_SECONDS) * 1000
  );
}
export class PaymentRefundService {
  constructor(
    private readonly orders: PaymentOrderRepository,
    private readonly refunds: PaymentRefundRepository,
    private readonly core: PaymentCorePort,
    private readonly wechat: WechatPaymentPort,
    private readonly settings: PaymentSettingsService,
    private readonly env: PaymentEnvironment,
  ) {}
  private async owned(userId: number, tradeNo: string) {
    const order = await this.orders.findOwnedOrder(userId, tradeNo);
    if (!order) throw new PaymentRequestError(404, "支付订单不存在");
    return order;
  }
  private async appKey() {
    const settings = await this.settings.resolve(
      this.env.PAYMENT_SETTINGS_ENCRYPTION_KEY,
      true,
    );
    if (!settings) throw new PaymentRequestError(503, "微信虚拟支付尚未配置");
    return settings.appKey;
  }
  private async points(
    action: "quote" | "reserve" | "complete" | "release",
    order: PaymentOrderRecord,
    refundId = "",
  ) {
    if (!this.core.refundPoints)
      throw new PaymentRequestError(503, "退款积分服务未就绪");
    return this.core.refundPoints(
      action,
      order.userId,
      `virtualpay:${order.outTradeNo}`,
      refundId,
    );
  }
  async sweep() {
    const pending = await this.refunds.pending(20);
    for (const tradeNo of pending) {
      const order = await this.orders.findOrder(tradeNo);
      if (!order) continue;
      try {
        await this.reconcile(order.userId, tradeNo);
      } catch {
        /* Retain durable state for the next scheduled reconciliation. */
      } finally {
        await this.refunds.touch(tradeNo);
      }
    }
  }
  async list(userId: number, before = Number.MAX_SAFE_INTEGER) {
    if (!this.orders.listOwned)
      throw new PaymentRequestError(503, "订单列表暂不可用");
    const rows = await this.orders.listOwned(userId, before, 21);
    const records = await Promise.all(
      rows
        .slice(0, 20)
        .map(async (order) => ({
          ...this.view(order),
          refund: await this.refunds.find(order.outTradeNo),
        })),
    );
    return { records, serverNow: new Date().toISOString(), nextCursor: rows.length > 20 ? rows[19].id : null };
  }
  private view(order: PaymentOrderRecord) {
    return {
      outTradeNo: order.outTradeNo,
      productId: order.productId,
      kind: order.kind ?? "subscription",
      tier: order.tier,
      amount: order.goodsPrice * order.quantity,
      quantity: order.quantity,
      points: order.points ?? 0,
      status: paymentStatus(order),
      expiresAt: paymentExpiresAt(order),
      createdAt: order.createdAt ?? null,
      paidAt: order.paidAt,
      deliveredAt: order.deliveredAt,
    };
  }
  async quote(userId: number, tradeNo: string) {
    const order = await this.owned(userId, tradeNo);
    const existing = await this.refunds.find(tradeNo);
    if (existing)
      return {
        eligible: false,
        reason: "REFUND_EXISTS",
        refund: existing,
        amount: existing.refundFee,
      };
    if (order.status !== "DELIVERED")
      return { eligible: false, reason: "NOT_DELIVERED", amount: 0 };
    const remote = await this.wechat.queryOrder(
      order.openid,
      tradeNo,
      await this.appKey(),
    );
    if (!Number.isSafeInteger(remote.paidTime) || Number(remote.paidTime) <= 0)
      return { eligible: false, reason: "REVIEW", amount: 0 };
    if (!refundWindowOpen(remote.paidTime))
      return { eligible: false, reason: "EXPIRED", amount: 0 };
    if (remote.orderType === 7)
      return { eligible: false, reason: "APPLE", amount: 0 };
    if (
      remote.orderType !== 0 ||
      !remote.paid ||
      remote.wxOrderId !== order.wxOrderId ||
      remote.paidFee !== order.goodsPrice * order.quantity ||
      remote.leftFee !== remote.paidFee
    )
      return { eligible: false, reason: "REVIEW", amount: 0 };
    const points = await this.points("quote", order);
    if (!points || typeof points === "boolean")
      return { eligible: false, reason: "LEGACY", amount: 0 };
    const amount = proportionalRefund(
      remote.paidFee,
      points.remaining,
      points.original,
    );
    return {
      eligible: amount > 0,
      reason: amount > 0 ? "AVAILABLE" : "NO_REMAINING",
      amount,
      remainingPoints: points.remaining / 1000,
      originalPoints: points.original / 1000,
      deadline: new Date(
        (Number(remote.paidTime) + REFUND_WINDOW_SECONDS) * 1000,
      ).toISOString(),
      terminatesSubscription: order.kind !== "points",
    };
  }
  async request(userId: number, tradeNo: string, expectedAmount: number) {
    const order = await this.owned(userId, tradeNo);
    const existing = await this.refunds.find(tradeNo);
    if (existing) return this.resume(order, existing);
    const quote = await this.quote(userId, tradeNo);
    if (!quote.eligible)
      throw new PaymentRequestError(
        409,
        quote.reason === "APPLE"
          ? "请向 Apple 申请退款"
          : quote.reason === "EXPIRED"
            ? "支付已超过7天，不支持申请退款"
            : "订单暂不支持自助退款，请查看订单说明",
      );
    if (quote.amount !== expectedAmount)
      throw new PaymentRequestError(409, "可退金额已变化，请重新确认");
    await this.refunds.create(
      tradeNo,
      `R${crypto.randomUUID().replaceAll("-", "").slice(0, 30)}`,
      expectedAmount,
    );
    const refund = await this.refunds.find(tradeNo);
    if (!refund) throw new PaymentRequestError(503, "退款创建失败");
    return this.resume(order, refund, expectedAmount);
  }
  private async resume(
    order: PaymentOrderRecord,
    refund: PaymentRefund,
    expectedAmount = refund.refundFee,
  ): Promise<PaymentRefund | null> {
    if (refund.external || refund.status !== "PREPARING")
      return this.reconcile(order.userId, order.outTradeNo);
    const hold = await this.points("reserve", order, refund.refundId);
    if (!hold || typeof hold === "boolean" || hold.state !== "HELD") {
      // A concurrent callback may already have settled Core. A stale PREPARING reader
      // must not overwrite another worker's claimed or completed refund.
      if (!(await this.refunds.claim(order.outTradeNo, 0, 0, 0)))
        return this.refunds.find(order.outTradeNo);
      await this.refunds.update(order.outTradeNo,
        hold && typeof hold !== "boolean" && hold.state === "COMPLETED" ? "REVIEW" : "FAILED");
      throw new PaymentRequestError(409, "没有可退款积分或订单需人工处理");
    }
    const amount = proportionalRefund(
      order.goodsPrice * order.quantity,
      hold.amount ?? 0,
      hold.original,
    );
    const remote = await this.wechat.queryOrder(
      order.openid,
      order.outTradeNo,
      await this.appKey(),
    );
    if (
      !refundWindowOpen(remote.paidTime) ||
      amount <= 0 ||
      (expectedAmount !== undefined && expectedAmount !== amount) ||
      remote.orderType !== 0 ||
      !remote.paid ||
      remote.wxOrderId !== order.wxOrderId ||
      remote.leftFee !== order.goodsPrice * order.quantity ||
      remote.paidFee !== remote.leftFee
    ) {
      // No external request has been sent while PREPARING. Release is safe and idempotent.
      if (!(await this.refunds.claim(order.outTradeNo, 0, 0, hold.amount ?? 0)))
        return this.refunds.find(order.outTradeNo);
      if ((await this.points("release", order, refund.refundId)) !== true)
        throw new PaymentRequestError(503, "退款积分恢复中");
      await this.refunds.update(order.outTradeNo, "FAILED");
      throw new PaymentRequestError(409, "退款条件已变化，请联系支持");
    }
    if (
      await this.refunds.claim(
        order.outTradeNo,
        amount,
        remote.leftFee,
        hold.amount ?? 0,
      )
    ) {
      try {
        if (!this.wechat.refundOrder)
          throw new Error("Refund adapter unavailable");
        const result = await this.wechat.refundOrder(
          {
            openid: order.openid,
            order_id: order.outTradeNo,
            refund_order_id: refund.refundId,
            left_fee: remote.leftFee,
            refund_fee: amount,
            biz_meta: order.outTradeNo,
            refund_reason: "3",
            req_from: "2",
            env: 0,
          },
          await this.appKey(),
        );
        // A timeout or unclassified rejection must never release points or create a second refund.
        await this.refunds.update(
          order.outTradeNo,
          result.accepted || result.code === 268490014
            ? "PROCESSING"
            : "REVIEW",
          result.wxRefundId ?? undefined,
        );
      } catch {
        /* SUBMITTING is durable and reconciled without resubmitting an uncertain request. */
      }
    }
    return this.refunds.find(order.outTradeNo);
  }
  async reconcile(
    userId: number,
    tradeNo: string,
  ): Promise<PaymentRefund | null> {
    const order = await this.owned(userId, tradeNo);
    const refund = await this.refunds.find(tradeNo);
    if (!refund || ["SUCCEEDED", "FAILED"].includes(refund.status))
      return refund;
    if (refund.external) return this.completeExternal(order, refund);
    if (refund.status === "PREPARING") return this.resume(order, refund);
    if (refund.refundFee === 0 && refund.status === "SUBMITTING") {
      await this.finish(order, refund, false);
      return this.refunds.find(tradeNo);
    }
    const key = await this.appKey();
    const result = await this.wechat.queryOrder(
      order.openid,
      refund.refundId,
      key,
    );
    if (![1, 8].includes(result.orderType ?? -1)) return refund;
    // Require the explicit user-refund-complete state and original payment balance corroboration.
    if (result.status === 8) {
      const paid = await this.wechat.queryOrder(order.openid, tradeNo, key);
      if (
        paid.wxOrderId !== order.wxOrderId ||
        paid.leftFee !== refund.leftFee - refund.refundFee
      )
        return refund;
      await this.finish(order, refund, true);
    } else if (result.status === 7) await this.finish(order, refund, false);
    return this.refunds.find(tradeNo);
  }
  private async completeExternal(
    order: PaymentOrderRecord,
    refund: PaymentRefund,
  ): Promise<PaymentRefund | null> {
    const key = await this.appKey();
    const result = await this.wechat.queryOrder(
      order.openid,
      refund.refundId,
      key,
    );
    const paid = await this.wechat.queryOrder(
      order.openid,
      order.outTradeNo,
      key,
    );
    if (
      ![1, 8].includes(result.orderType ?? -1) ||
      result.status !== 8 ||
      !result.wxOrderId ||
      (refund.wxRefundId && refund.wxRefundId !== result.wxOrderId) ||
      paid.wxOrderId !== order.wxOrderId ||
      paid.leftFee !== 0 ||
      refund.refundFee !== order.goodsPrice * order.quantity
    )
      return refund;
    const hold = await this.points("reserve", order, refund.refundId);
    if (!hold || typeof hold === "boolean") {
      await this.refunds.update(order.outTradeNo, "REVIEW", result.wxOrderId);
      return this.refunds.find(order.outTradeNo);
    }
    await this.refunds.claim(
      order.outTradeNo,
      refund.refundFee,
      refund.leftFee,
      hold.amount ?? 0,
    );
    await this.finish(order, refund, true);
    return this.refunds.find(order.outTradeNo);
  }
  private async finish(
    order: PaymentOrderRecord,
    refund: PaymentRefund,
    success: boolean,
  ) {
    if (
      (await this.points(
        success ? "complete" : "release",
        order,
        refund.refundId,
      )) !== true
    )
      throw new PaymentRequestError(503, "退款权益处理稍后重试");
    if (success) await this.orders.markRefunded?.(order.id);
    await this.refunds.update(
      order.outTradeNo,
      success ? "SUCCEEDED" : "FAILED",
    );
  }
  async notification(input: {
    tradeNo: string;
    openid: string;
    refundId: string;
    wxOrderId: string;
    wxRefundId: string;
    fee: number;
    success: boolean;
  }) {
    const order = await this.orders.findOrder(input.tradeNo);
    if (
      !order ||
      order.openid !== input.openid ||
      (order.wxOrderId && order.wxOrderId !== input.wxOrderId)
    )
      throw new PaymentRequestError(400, "退款通知与订单不一致");
    let refund = await this.refunds.find(order.outTradeNo);
    if (!refund && input.success) {
      const paid = await this.wechat.queryOrder(
        order.openid,
        order.outTradeNo,
        await this.appKey(),
      );
      if (
        ![0, 7].includes(paid.orderType ?? -1) ||
        paid.wxOrderId !== input.wxOrderId ||
        paid.leftFee !== 0 ||
        input.fee !== order.goodsPrice * order.quantity
      )
        throw new PaymentRequestError(409, "外部部分退款需人工核对");
      const providerRefund = await this.wechat.queryOrder(
        order.openid,
        input.refundId,
        await this.appKey(),
      );
      if (
        ![1, 8].includes(providerRefund.orderType ?? -1) ||
        providerRefund.status !== 8 ||
        providerRefund.wxOrderId !== input.wxRefundId
      )
        throw new PaymentRequestError(503, "外部退款结果核对中");
      await this.refunds.create(
        order.outTradeNo,
        input.refundId,
        input.fee,
        true,
      );
      refund = await this.refunds.find(order.outTradeNo);
    }
    if (
      !refund ||
      refund.refundId !== input.refundId ||
      refund.refundFee !== input.fee ||
      (refund.wxRefundId && refund.wxRefundId !== input.wxRefundId)
    )
      throw new PaymentRequestError(409, "外部退款需核对权益");
    if (refund.status === "SUCCEEDED" || refund.status === "FAILED") return;
    if (refund.external) {
      const result = await this.completeExternal(order, refund);
      if (result?.status !== "SUCCEEDED")
        throw new PaymentRequestError(503, "外部退款权益核对中");
      return;
    }
    // The callback signature covers URL fields, not the body: verify money movement server-to-server.
    const paid = await this.wechat.queryOrder(
      order.openid,
      order.outTradeNo,
      await this.appKey(),
    );
    if (
      input.success &&
      paid.wxOrderId === input.wxOrderId &&
      paid.leftFee === refund.leftFee - refund.refundFee
    ) {
      await this.finish(order, refund, true);
      return;
    }
    await this.reconcile(order.userId, order.outTradeNo);
    const latest = await this.refunds.find(order.outTradeNo);
    if (!latest || !["SUCCEEDED", "FAILED"].includes(latest.status))
      throw new PaymentRequestError(503, "退款结果核对中");
  }
}
