import type { D1Binding } from "@dinqor/worker-runtime";
import type {
  PaymentRefund,
  PaymentRefundRepository,
} from "../interfaces/payment-refund-repository.js";
export class D1PaymentRefundMapper implements PaymentRefundRepository {
  constructor(private readonly db: D1Binding) {}
  find(outTradeNo: string) {
    return this.db
      .prepare(
        `SELECT refund_id refundId,out_trade_no outTradeNo,status,external,
  refund_fee refundFee,left_fee leftFee,points_amount pointsAmount,wx_refund_id wxRefundId FROM virtual_pay_refund WHERE out_trade_no=?`,
      )
      .bind(outTradeNo)
      .first<PaymentRefund>();
  }
  async create(
    outTradeNo: string,
    refundId: string,
    expectedAmount: number,
    external = false,
  ) {
    await this.db
      .prepare(
        `INSERT INTO virtual_pay_refund(out_trade_no,refund_id,status,refund_fee,left_fee,external) VALUES(?,?,'PREPARING',?,?,?) ON CONFLICT DO NOTHING`,
      )
      .bind(
        outTradeNo,
        refundId,
        expectedAmount,
        external ? expectedAmount : 0,
        external ? 1 : 0,
      )
      .run();
  }
  async claim(
    outTradeNo: string,
    refundFee: number,
    leftFee: number,
    pointsAmount: number,
  ) {
    const r = await this.db
      .prepare(
        `UPDATE virtual_pay_refund SET status='SUBMITTING',refund_fee=?,left_fee=?,points_amount=?,
   updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE out_trade_no=? AND status='PREPARING'`,
      )
      .bind(refundFee, leftFee, pointsAmount, outTradeNo)
      .run();
    return r.meta.changes === 1;
  }
  async update(
    outTradeNo: string,
    status: PaymentRefund["status"],
    wxRefundId?: string,
  ) {
    await this.db
      .prepare(
        `UPDATE virtual_pay_refund SET status=?,wx_refund_id=COALESCE(?,wx_refund_id),
   updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE out_trade_no=? AND status NOT IN ('SUCCEEDED','FAILED')`,
      )
      .bind(status, wxRefundId ?? null, outTradeNo)
      .run();
  }
  async touch(outTradeNo: string) {
    await this.db
      .prepare(
        `UPDATE virtual_pay_refund SET updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE out_trade_no=?`,
      )
      .bind(outTradeNo)
      .run();
  }
  async pending(limit: number) {
    const r = await this.db
      .prepare(
        `SELECT out_trade_no outTradeNo FROM virtual_pay_refund WHERE status IN ('PREPARING','SUBMITTING','PROCESSING','REVIEW')
   ORDER BY updated_at LIMIT ?`,
      )
      .bind(limit)
      .all<{ outTradeNo: string }>();
    return r.results.map((x) => x.outTradeNo);
  }
}
