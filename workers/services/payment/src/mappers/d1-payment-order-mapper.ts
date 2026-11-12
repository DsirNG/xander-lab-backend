import type { D1Binding } from "@dinqor/worker-runtime";
import type {
  NewPaymentOrder,
  PaymentOrderRecord,
  PaymentOrderRepository,
} from "../interfaces/payment-order-repository.js";

const SELECT_ORDER = `
  SELECT id, out_trade_no AS outTradeNo, wx_order_id AS wxOrderId,
         user_id AS userId, openid, product_id AS productId, tier,
         goods_price AS goodsPrice, quantity, duration_days AS durationDays,
         purchase_kind AS kind, points, created_at AS createdAt, status, paid_at AS paidAt, delivered_at AS deliveredAt
  FROM virtual_pay_order`;

export class D1PaymentOrderMapper implements PaymentOrderRepository {
  constructor(private readonly db: D1Binding) {}

  async listOwned(userId: number,before: number,limit: number) {
    const result=await this.db.prepare(`${SELECT_ORDER} WHERE user_id=? AND id<? ORDER BY id DESC LIMIT ?`)
      .bind(userId,before,limit).all<PaymentOrderRecord>(); return result.results;
  }
  async markRefunded(id: number) {
    await this.db.prepare(`UPDATE virtual_pay_order SET status='REFUNDED',updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=? AND status IN ('PENDING','DELIVERED')`).bind(id).run();
  }
  findPayable(userId: number, productId: string, quantity: number) {
    return this.db.prepare(`${SELECT_ORDER} WHERE user_id=? AND product_id=? AND quantity=?
      AND status='PENDING' AND julianday(created_at)>julianday('now','-15 minutes')
      ORDER BY id DESC LIMIT 1`).bind(userId,productId,quantity).first<PaymentOrderRecord>();
  }
  async insertPending(input: NewPaymentOrder): Promise<boolean> {
    const result = await this.db
      .prepare(
        `
      INSERT INTO virtual_pay_order (
        out_trade_no, user_id, openid, product_id, tier,
        goods_price, quantity, duration_days, status, purchase_kind, points
       ) SELECT ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?
      WHERE NOT EXISTS (SELECT 1 FROM virtual_pay_order WHERE user_id=? AND product_id=? AND quantity=?
        AND status='PENDING' AND julianday(created_at)>julianday('now','-15 minutes'))
    `,
      )
      .bind(
        input.outTradeNo,
        input.userId,
        input.openid,
        input.productId,
        input.tier,
        input.goodsPrice,
        input.quantity,
        input.durationDays,
        input.kind ?? "subscription",
        input.points ?? 0,
        input.userId, input.productId, input.quantity,
      )
      .run();
    return result.success && result.meta.changes === 1;
  }

  findOwnedOrder(userId: number, outTradeNo: string): Promise<PaymentOrderRecord | null> {
    return this.db
      .prepare(`${SELECT_ORDER} WHERE user_id = ? AND out_trade_no = ?`)
      .bind(userId, outTradeNo)
      .first<PaymentOrderRecord>();
  }

  findOrder(outTradeNo: string): Promise<PaymentOrderRecord | null> {
    return this.db
      .prepare(`${SELECT_ORDER} WHERE out_trade_no = ?`)
      .bind(outTradeNo)
      .first<PaymentOrderRecord>();
  }

  async markDelivered(id: number, wxOrderId: string, paidAt?: string): Promise<boolean> {
    const result = await this.db
      .prepare(
        `
      UPDATE virtual_pay_order
         SET wx_order_id = COALESCE(wx_order_id, ?),
             status = 'DELIVERED',
             paid_at = COALESCE(?, paid_at),
             delivered_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now'),
             updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
       WHERE id = ? AND status = 'PENDING'
    `,
      )
      .bind(wxOrderId, paidAt ?? null, id)
      .run();
    return result.success && result.meta.changes === 1;
  }
}
