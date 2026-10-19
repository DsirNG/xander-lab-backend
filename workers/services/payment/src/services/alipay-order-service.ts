import type { AlipayOrderRepository, WebOrder, WebRefund } from "../interfaces/alipay-order-repository.js";
import type { AlipayConfiguration, AlipayPort, AlipayResult } from "../interfaces/alipay-port.js";
import type { PaymentCorePort } from "../interfaces/payment-core-port.js";
import { amountCents, amountText } from "./alipay-configuration.js";
import { PaymentRequestError } from "./payment-order-service.js";
import { AlipayOperationError } from "../interfaces/alipay-port.js";

function view(order: WebOrder) {
  return { outTradeNo: order.outTradeNo, productId: order.productId, tier: order.tier,
    priceCents: order.priceCents, status: order.status, deliveredAt: order.deliveredAt,
    environment: order.environment };
}

export class AlipayOrderService {
  constructor(private readonly orders: AlipayOrderRepository, private readonly core: PaymentCorePort,
    private readonly provider: AlipayPort, private readonly config: AlipayConfiguration) {}

  catalog() { return { enabled: true, environment: this.config.environment, products: this.config.products }; }

  async create(userId: number, productId: string, idempotencyKey: string) {
    const product = this.config.products.find((item) => item.productId === productId);
    if (!product) throw new PaymentRequestError(400,"Payment product is unavailable");
    const order = await this.orders.create({ ...product, userId, idempotencyKey,
      outTradeNo: `A${crypto.randomUUID().replaceAll("-","")}`, appId: this.config.appId,
      sellerId: this.config.sellerId, environment: this.config.environment,
      status: "PENDING", tradeNo: null, deliveredAt: null });
    this.checkContext(order);
    if (order.productId !== productId) throw new PaymentRequestError(409,"Idempotency key belongs to another product");
    if (order.status !== "PENDING") return { ...view(order), paymentHtml: null };
    return { ...view(order), paymentHtml: this.provider.page({ ...order, subject: `DinQorAI ${order.tier}` }) };
  }

  async status(userId: number, outTradeNo: string) { return view(await this.owned(userId,outTradeNo)); }

  async reconcile(userId: number, outTradeNo: string) {
    const order = await this.owned(userId,outTradeNo);
    if (order.status === "PAID") { await this.deliver(order); return view((await this.orders.find(outTradeNo))!); }
    if (order.status !== "PENDING") return view(order);
    let result;
    try { result = await this.provider.call("alipay.trade.query",{ out_trade_no: outTradeNo }); }
    catch (error) {
      if (error instanceof AlipayOperationError && error.tradeNotFound) return view(order);
      throw error;
    }
    this.checkTrade(order,result);
    if (["TRADE_SUCCESS","TRADE_FINISHED"].includes(String(result.trade_status))) {
      await this.orders.markPaid(outTradeNo,String(result.trade_no));
      await this.deliver((await this.orders.find(outTradeNo))!);
    } else if (result.trade_status === "TRADE_CLOSED") await this.orders.close(outTradeNo);
    // Unknown and WAIT_BUYER_PAY remain pending. Neither is evidence of a successful payment.
    return view((await this.orders.find(outTradeNo))!);
  }

  async notify(params: Record<string,string>) {
    if (params.app_id !== this.config.appId || params.sign_type !== "RSA2"
      || !this.provider.verifyNotification(params)) throw new PaymentRequestError(400,"Invalid payment signature");
    const order = await this.orders.find(params.out_trade_no);
    if (!order || !params.notify_id || params.notify_id.length > 128
      || params.notify_type !== "trade_status_sync") throw new PaymentRequestError(400,"Invalid payment notification");
    this.checkContext(order);
    this.checkTrade(order,params);
    if (params.seller_id !== this.config.sellerId) throw new PaymentRequestError(400,"Payment seller mismatch");
    if (["TRADE_SUCCESS","TRADE_FINISHED"].includes(params.trade_status)
      && !params.out_biz_no && !params.gmt_refund && !params.refund_fee) {
      await this.orders.markPaid(order.outTradeNo,params.trade_no);
      await this.deliver((await this.orders.find(order.outTradeNo))!);
    } else if (params.trade_status === "TRADE_CLOSED") await this.orders.close(order.outTradeNo);
    // Record only after fulfillment succeeds so a failed Core grant remains retryable.
    await this.orders.recordNotification(order,params);
  }

  async close(userId: number, outTradeNo: string) {
    const current = await this.reconcile(userId,outTradeNo);
    if (current.status === "CLOSED") return current;
    if (current.status !== "PENDING") throw new PaymentRequestError(409,"Paid orders cannot be closed");
    const result = await this.provider.call("alipay.trade.close",{ out_trade_no: outTradeNo });
    if (result.out_trade_no !== outTradeNo) throw new PaymentRequestError(502,"Payment order mismatch");
    await this.orders.close(outTradeNo);
    return view((await this.orders.find(outTradeNo))!);
  }

  /** Admin-only full refund. Subscription review is explicit and remains an operator task. */
  async refund(outTradeNo: string) {
    const order = await this.orders.find(outTradeNo);
    if (!order) throw new PaymentRequestError(404,"Payment order does not exist");
    this.checkContext(order);
    if (!["DELIVERED","REFUND_PENDING","REFUNDED"].includes(order.status)) {
      throw new PaymentRequestError(409,"Only delivered orders can be refunded");
    }
    const refund = await this.orders.reserveRefund(order);
    if (refund.status === "SUCCEEDED") return this.refundView(refund);
    const result = await this.provider.call("alipay.trade.refund",{
      out_trade_no: outTradeNo, out_request_no: refund.outRequestNo, refund_amount: amountText(refund.amountCents),
    });
    this.checkRefund(order,refund,result,false);
    if (result.fund_change === "Y") await this.orders.completeRefund(refund);
    return this.refundView((await this.orders.findRefund(outTradeNo))!);
  }

  async queryRefund(outTradeNo: string) {
    const order = await this.orders.find(outTradeNo);
    if (!order) throw new PaymentRequestError(404,"Payment order does not exist");
    this.checkContext(order);
    const refund = await this.orders.findRefund(outTradeNo);
    if (!refund) throw new PaymentRequestError(404,"Refund does not exist");
    if (refund.status === "SUCCEEDED") return this.refundView(refund);
    const result = await this.provider.call("alipay.trade.fastpay.refund.query",{
      out_trade_no: outTradeNo, out_request_no: refund.outRequestNo,
    });
    this.checkRefund(order,refund,result,true);
    if (result.refund_status === "REFUND_SUCCESS") await this.orders.completeRefund(refund);
    return this.refundView((await this.orders.findRefund(outTradeNo))!);
  }

  private refundView(refund: WebRefund) {
    return { outRequestNo: refund.outRequestNo, outTradeNo: refund.outTradeNo, amountCents: refund.amountCents,
      status: refund.status, entitlementReviewRequired: Boolean(refund.entitlementReviewRequired) };
  }

  private checkRefund(order: WebOrder, refund: WebRefund, result: AlipayResult, query: boolean) {
    if (result.out_trade_no !== order.outTradeNo || result.trade_no !== order.tradeNo
      || (query && result.out_request_no !== refund.outRequestNo)
      || amountCents(query ? result.refund_amount : result.refund_fee) !== refund.amountCents) {
      throw new PaymentRequestError(502,"Refund result mismatch");
    }
  }

  private async owned(userId: number, outTradeNo: string) {
    const order = await this.orders.find(outTradeNo);
    if (!order || order.userId !== userId) throw new PaymentRequestError(404,"Payment order does not exist");
    this.checkContext(order);
    return order;
  }

  private checkContext(order: WebOrder) {
    if (order.appId !== this.config.appId || order.sellerId !== this.config.sellerId
      || order.environment !== this.config.environment) throw new PaymentRequestError(409,"Payment environment mismatch");
  }

  private checkTrade(order: WebOrder, result: AlipayResult) {
    if (result.out_trade_no !== order.outTradeNo || amountCents(result.total_amount) !== order.priceCents
      || typeof result.trade_no !== "string" || !/^[A-Za-z0-9_-]{1,128}$/.test(result.trade_no)
      || (order.tradeNo !== null && order.tradeNo !== result.trade_no)) {
      throw new PaymentRequestError(400,"Payment order or amount mismatch");
    }
  }

  private async deliver(order: WebOrder) {
    if (order.status !== "PAID") return;
    // Core's unique entitlement event makes concurrent callbacks/query retries safe after a crash.
    await this.core.grantEntitlement({ userId: order.userId, tier: order.tier, durationDays: order.durationDays,
      eventKey: `virtualpay:ALIPAY_${order.outTradeNo}` });
    await this.orders.markDelivered(order.outTradeNo);
    if ((await this.orders.find(order.outTradeNo))?.status !== "DELIVERED") throw new Error("Delivery persistence failed");
  }
}
