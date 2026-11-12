import type { D1Binding } from "@dinqor/worker-runtime";
import { pointsExpiryStatements } from "./points-expiry.js";
export interface RefundPoints {
  original: number;
  remaining: number;
  amount?: number;
  state?: string;
}
/** Core owns order-attributed points and atomic refund reservations. */
export class D1PaymentRefundMapper {
  constructor(private readonly db: D1Binding) {}
  async quote(userId: number, eventKey: string): Promise<RefundPoints | null> {
    return this.db
      .prepare(
        `SELECT original,remaining FROM refundable_points_lot WHERE user_id=? AND event_key=?`,
      )
      .bind(userId, eventKey)
      .first<RefundPoints>();
  }
  async reserve(
    userId: number,
    eventKey: string,
    refundId: string,
  ): Promise<RefundPoints | null> {
    await this.db.batch([
      ...pointsExpiryStatements(this.db, userId),
      this.db
        .prepare(
          `
   INSERT INTO payment_refund_hold(refund_id,event_key,user_id,amount)
   SELECT ?,event_key,user_id,remaining FROM refundable_points_lot
   WHERE user_id=? AND event_key=? AND remaining>=0
     AND remaining<=(SELECT balance FROM available_points WHERE user_id=?)
   ON CONFLICT DO NOTHING`,
        )
        .bind(refundId, userId, eventKey, userId),
    ]);
    return this.db
      .prepare(
        `SELECT l.original,l.remaining,h.amount,h.state FROM payment_refund_hold h
    JOIN refundable_points_lot l ON l.event_key=h.event_key WHERE h.refund_id=? AND h.user_id=? AND h.event_key=?`,
      )
      .bind(refundId, userId, eventKey)
      .first<RefundPoints>();
  }
  async settle(
    userId: number,
    eventKey: string,
    refundId: string,
    success: boolean,
  ) {
    await this.db
      .prepare(
        `UPDATE payment_refund_hold SET state=? WHERE refund_id=? AND user_id=? AND event_key=? AND state='HELD'`,
      )
      .bind(success ? "COMPLETED" : "RELEASED", refundId, userId, eventKey)
      .run();
    const row = await this.db
      .prepare(
        `SELECT state FROM payment_refund_hold WHERE refund_id=? AND user_id=? AND event_key=?`,
      )
      .bind(refundId, userId, eventKey)
      .first<{ state: string }>();
    return row?.state === (success ? "COMPLETED" : "RELEASED");
  }
}
