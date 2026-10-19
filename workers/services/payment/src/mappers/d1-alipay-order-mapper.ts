import type { D1Binding } from "@dinqor/worker-runtime";
import type { AlipayOrderRepository, WebOrder, WebRefund } from "../interfaces/alipay-order-repository.js";

const SELECT = `SELECT out_trade_no AS outTradeNo, user_id AS userId, idempotency_key AS idempotencyKey,
  product_id AS productId, tier, price_cents AS priceCents, duration_days AS durationDays,
  app_id AS appId, seller_id AS sellerId, environment, status, trade_no AS tradeNo,
  delivered_at AS deliveredAt FROM alipay_web_order`;
const REFUND = `SELECT out_request_no AS outRequestNo, out_trade_no AS outTradeNo,
  amount_cents AS amountCents, status, entitlement_review_required AS entitlementReviewRequired FROM alipay_web_refund`;

export class D1AlipayOrderMapper implements AlipayOrderRepository {
  constructor(private readonly db: D1Binding) {}

  async create(input: WebOrder): Promise<WebOrder> {
    await this.db.prepare(`INSERT INTO alipay_web_order
      (out_trade_no,user_id,idempotency_key,product_id,tier,price_cents,duration_days,app_id,seller_id,environment)
      VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(user_id,idempotency_key) DO NOTHING`)
      .bind(input.outTradeNo,input.userId,input.idempotencyKey,input.productId,input.tier,input.priceCents,
        input.durationDays,input.appId,input.sellerId,input.environment).run();
    const stored = await this.db.prepare(`${SELECT} WHERE user_id=? AND idempotency_key=?`)
      .bind(input.userId,input.idempotencyKey).first<WebOrder>();
    if (!stored) throw new Error("Order persistence failed");
    return stored;
  }

  find(outTradeNo: string) { return this.db.prepare(`${SELECT} WHERE out_trade_no=?`).bind(outTradeNo).first<WebOrder>(); }

  async markPaid(outTradeNo: string, tradeNo: string) {
    await this.db.prepare(`UPDATE alipay_web_order SET status='PAID',trade_no=?
      WHERE out_trade_no=? AND status='PENDING' AND (trade_no IS NULL OR trade_no=?)`)
      .bind(tradeNo,outTradeNo,tradeNo).run();
  }

  async markDelivered(outTradeNo: string) {
    await this.db.prepare(`UPDATE alipay_web_order SET status='DELIVERED',
      delivered_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE out_trade_no=? AND status='PAID'`)
      .bind(outTradeNo).run();
  }

  async close(outTradeNo: string) {
    await this.db.prepare(`UPDATE alipay_web_order SET status='CLOSED' WHERE out_trade_no=? AND status='PENDING'`)
      .bind(outTradeNo).run();
  }

  async recordNotification(order: WebOrder, params: Record<string,string>) {
    await this.db.prepare(`INSERT INTO alipay_web_notification
      (app_id,environment,notify_id,out_trade_no,trade_no,trade_status) VALUES(?,?,?,?,?,?)
      ON CONFLICT(app_id,environment,notify_id) DO NOTHING`)
      .bind(order.appId,order.environment,params.notify_id,order.outTradeNo,params.trade_no,params.trade_status).run();
  }

  async reserveRefund(order: WebOrder): Promise<WebRefund> {
    // One full refund per order bounds cumulative refunds and gives retries a stable provider key.
    await this.db.batch([
      this.db.prepare(`INSERT INTO alipay_web_refund(out_request_no,out_trade_no,amount_cents)
        SELECT ?,out_trade_no,price_cents FROM alipay_web_order WHERE out_trade_no=? AND status='DELIVERED'
        ON CONFLICT(out_trade_no) DO NOTHING`).bind(`R${order.outTradeNo}`,order.outTradeNo),
      this.db.prepare(`UPDATE alipay_web_order SET status='REFUND_PENDING'
        WHERE out_trade_no=? AND status='DELIVERED' AND EXISTS
        (SELECT 1 FROM alipay_web_refund WHERE out_trade_no=?)`).bind(order.outTradeNo,order.outTradeNo),
    ]);
    const refund = await this.findRefund(order.outTradeNo);
    if (!refund) throw new Error("Refund cannot be reserved");
    return refund;
  }

  findRefund(outTradeNo: string) {
    return this.db.prepare(`${REFUND} WHERE out_trade_no=?`).bind(outTradeNo).first<WebRefund>();
  }

  async completeRefund(refund: WebRefund) {
    await this.db.batch([
      this.db.prepare(`UPDATE alipay_web_refund SET status='SUCCEEDED' WHERE out_request_no=? AND status='PENDING'`)
        .bind(refund.outRequestNo),
      this.db.prepare(`UPDATE alipay_web_order SET status='REFUNDED' WHERE out_trade_no=? AND status='REFUND_PENDING'`)
        .bind(refund.outTradeNo),
    ]);
  }
}
