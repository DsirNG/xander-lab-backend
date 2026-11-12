import type { D1Binding } from "@dinqor/worker-runtime";
import type {
  BillingLedgerInput,
  BillingModelPrice,
  BillingModelPriceInput,
  BillingRepository,
} from "../interfaces/billing-repository.js";

import { pointsExpiryStatements } from "./points-expiry.js";

const MODEL_PRICE_SELECT = `
  SELECT id, model,
         input_milli_per_1k_token AS inputMilliPer1kToken,
         output_milli_per_1k_token AS outputMilliPer1kToken,
         cached_milli_per_1k_token AS cachedMilliPer1kToken,
         cached_write_milli_per_1k_token AS cachedWriteMilliPer1kToken,
         enabled, remark, updated_at AS updatedAt
  FROM billing_model_price`;

export class D1BillingMapper implements BillingRepository {
  constructor(private readonly db: D1Binding) {}

  findModelPrice(model: string): Promise<BillingModelPrice | null> {
    return this.db.prepare(`${MODEL_PRICE_SELECT} WHERE model = ? LIMIT 1`)
      .bind(model).first<BillingModelPrice>();
  }

  async listModelPrices(): Promise<BillingModelPrice[]> {
    const rows = await this.db.prepare(`${MODEL_PRICE_SELECT} ORDER BY model ASC`).all<BillingModelPrice>();
    return rows.results ?? [];
  }

  async deleteModelPrice(model: string): Promise<void> {
    await this.db.prepare("DELETE FROM billing_model_price WHERE model = ?").bind(model).run();
  }

  upsertModelPrice(input: BillingModelPriceInput): Promise<BillingModelPrice | null> {
    return this.db.prepare(`
      INSERT INTO billing_model_price (
        model, input_milli_per_1k_token, output_milli_per_1k_token,
        cached_milli_per_1k_token, cached_write_milli_per_1k_token, enabled, remark
      ) VALUES (?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(model) DO UPDATE SET
        input_milli_per_1k_token = excluded.input_milli_per_1k_token,
        output_milli_per_1k_token = excluded.output_milli_per_1k_token,
        cached_milli_per_1k_token = excluded.cached_milli_per_1k_token,
        cached_write_milli_per_1k_token = excluded.cached_write_milli_per_1k_token,
        enabled = excluded.enabled,
        remark = excluded.remark,
        updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
      RETURNING id, model,
                input_milli_per_1k_token AS inputMilliPer1kToken,
                output_milli_per_1k_token AS outputMilliPer1kToken,
                cached_milli_per_1k_token AS cachedMilliPer1kToken,
                cached_write_milli_per_1k_token AS cachedWriteMilliPer1kToken,
                enabled, remark, updated_at AS updatedAt
    `).bind(input.model, input.inputMilliPer1kToken, input.outputMilliPer1kToken,
      input.cachedMilliPer1kToken, input.cachedWriteMilliPer1kToken,
      input.enabled ? 1 : 0, input.remark).first<BillingModelPrice>();
  }

  async appendLedger(input: BillingLedgerInput): Promise<void> {
    await this.db.prepare(`
      INSERT INTO sys_user_points (user_id, balance)
      VALUES (?, 0)
      ON CONFLICT(user_id) DO NOTHING
    `).bind(input.userId).run();
    await this.db.batch([...pointsExpiryStatements(this.db, input.userId), this.db.prepare(`
      INSERT INTO points_ledger (
        user_id, biz_type, biz_key, biz_ref, amount, balance_after,
        unit_price, model, input_tokens, output_tokens, cached_tokens, remark, balance_delta
      )
      SELECT ?, ?, ?, ?, ?, points.balance + ?, ?, ?, ?, ?, ?, ?, ?
      FROM sys_user_points AS points
      WHERE points.user_id = ?
      ON CONFLICT(biz_type, biz_key) DO NOTHING
      RETURNING id
    `).bind(input.userId, input.bizType, input.bizKey, input.bizRef, input.amount,
      input.balanceDelta, input.unitPrice, input.model, input.inputTokens,
      input.outputTokens, input.cachedTokens, input.remark, input.balanceDelta, input.userId)]);
  }

  async grantRegistrationPoints(
    userId: number,
    amount: number,
    unitPrice: string,
    enabled: boolean,
  ): Promise<void> {
    const statements = [
      ...pointsExpiryStatements(this.db, userId),
      this.db.prepare(`
        INSERT INTO sys_user_points (user_id, balance)
        SELECT id, 0 FROM sys_user
        WHERE id = ? AND registration_grant_pending = 1
        ON CONFLICT(user_id) DO NOTHING
      `).bind(userId),
      ...(enabled && amount > 0 ? [this.db.prepare(`
        INSERT INTO points_ledger (
          user_id, biz_type, biz_key, biz_ref, amount, balance_after,
          unit_price, model, input_tokens, output_tokens, cached_tokens, remark
        )
        SELECT user.id, 'grant', 'user:' || user.id, NULL, ?, points.balance + ?,
               ?, NULL, NULL, NULL, NULL, '注册赠送'
        FROM sys_user AS user
        JOIN sys_user_points AS points ON points.user_id = user.id
        WHERE user.id = ? AND user.status = 1 AND user.registration_grant_pending = 1
        ON CONFLICT(biz_type, biz_key) DO NOTHING
      `).bind(amount, amount, unitPrice, userId)] : []),
      this.db.prepare(`
        UPDATE sys_user SET registration_grant_pending = 0
        WHERE id = ? AND registration_grant_pending = 1
      `).bind(userId),
    ];
    await this.db.batch(statements);
  }

  async wallet(userId: number) {
    await this.db.batch(pointsExpiryStatements(this.db, userId));
    return await this.db.prepare(`SELECT balance, subscription_balance AS subscriptionBalance,
      permanent_balance AS permanentBalance, next_expiry AS nextExpiry FROM available_points WHERE user_id=?`)
      .bind(userId).first<{balance:number;subscriptionBalance:number;permanentBalance:number;nextExpiry:string|null}>()
      ?? { balance:0, subscriptionBalance:0, permanentBalance:0, nextExpiry:null };
  }

  async findBalance(userId: number): Promise<number> {
    const row = await this.db.prepare("SELECT balance FROM available_points WHERE user_id = ?")
      .bind(userId).first<{ balance: number }>();
    return row?.balance ?? 0;
  }

  async findConsumedToday(userId: number): Promise<number> {
    const row = await this.db.prepare(`
      SELECT COALESCE(SUM(amount), 0) AS consumedToday
      FROM points_ledger
      WHERE user_id = ? AND biz_type NOT IN ('grant','merge-credit','subscription-grant','points-purchase','subscription-expire','refund-hold','refund-release') AND created_at >= strftime('%Y-%m-%dT00:00:00.000Z', 'now')
        AND created_at < strftime('%Y-%m-%dT00:00:00.000Z', 'now', '+1 day')
    `).bind(userId).first<{ consumedToday: number }>();
    return row?.consumedToday ?? 0;
  }

  async pageLedger(
    userId: number,
    size: number,
    offset: number,
  ): Promise<{ records: Record<string, unknown>[]; total: number }> {
    await this.db.batch(pointsExpiryStatements(this.db,userId));
    const [rows, count] = await Promise.all([
      this.db.prepare(`
        SELECT id, biz_type AS bizType, biz_key AS bizKey, biz_ref AS bizRef,
               amount, balance_after AS balanceAfter, unit_price AS unitPrice,
               model, input_tokens AS inputTokens, output_tokens AS outputTokens,
               cached_tokens AS cachedTokens, remark, balance_delta AS balanceDelta, created_at AS createdAt
        FROM points_ledger WHERE user_id = ? ORDER BY id DESC LIMIT ? OFFSET ?
      `).bind(userId, size, offset).all<Record<string, unknown>>(),
      this.db.prepare("SELECT COUNT(*) AS total FROM points_ledger WHERE user_id = ?")
        .bind(userId).first<{ total: number }>(),
    ]);
    return { records: rows.results ?? [], total: count?.total ?? 0 };
  }

  async grantByKey(userId: number, bizKey: string, amount: number, unitPrice: string): Promise<void> {
    await this.db.batch(pointsExpiryStatements(this.db, userId));
    await this.db.prepare(`
      INSERT INTO sys_user_points (user_id, balance)
      VALUES (?, 0)
      ON CONFLICT(user_id) DO NOTHING
    `).bind(userId).run();
    await this.db.prepare(`
      INSERT INTO points_ledger (
        user_id, biz_type, biz_key, biz_ref, amount, balance_after,
        unit_price, model, input_tokens, output_tokens, cached_tokens, remark
      )
      SELECT ?, 'grant', ?, NULL, ?, points.balance + ?, ?, NULL, NULL, NULL, NULL, '注册赠送'
      FROM sys_user_points AS points WHERE points.user_id = ?
      ON CONFLICT(biz_type, biz_key) DO NOTHING
    `).bind(userId, bizKey, amount, amount, unitPrice, userId).run();
  }

  async transferBalance(
    fromUserId: number,
    toUserId: number,
    bizKey: string,
    unitPrice: string,
    remark: string,
  ): Promise<void> {
    await this.db.prepare(`
      INSERT INTO sys_user_points (user_id, balance)
      VALUES (?, 0)
      ON CONFLICT(user_id) DO NOTHING
    `).bind(toUserId).run();
    await this.db.batch([
      ...pointsExpiryStatements(this.db, fromUserId),
      ...pointsExpiryStatements(this.db, toUserId),
      this.db.prepare(`
        INSERT INTO points_ledger (
          user_id, biz_type, biz_key, biz_ref, amount, balance_after,
          unit_price, model, input_tokens, output_tokens, cached_tokens, remark
        )
        SELECT target.user_id, 'merge-credit', ?, NULL, source.balance,
               target.balance + source.balance, ?, NULL, NULL, NULL, NULL, ?
        FROM sys_user_points AS source
        JOIN sys_user_points AS target ON target.user_id = ?
        WHERE source.user_id = ? AND source.balance > 0
        ON CONFLICT(biz_type, biz_key) DO NOTHING
      `).bind(bizKey, unitPrice, remark, toUserId, fromUserId),
      this.db.prepare(`UPDATE purchased_points_lot SET user_id=? WHERE user_id=? AND EXISTS (
          SELECT 1 FROM points_ledger WHERE biz_type='merge-credit' AND biz_key=? AND user_id=?)`)
          .bind(toUserId,fromUserId,bizKey,toUserId),
        this.db.prepare(`UPDATE subscription_points_lot SET user_id=? WHERE user_id=? AND EXISTS (
        SELECT 1 FROM points_ledger WHERE biz_type='merge-credit' AND biz_key=? AND user_id=?)`)
        .bind(toUserId,fromUserId,bizKey,toUserId),
      this.db.prepare(`
        DELETE FROM sys_user_points
        WHERE user_id = ? AND EXISTS (
          SELECT 1 FROM points_ledger WHERE biz_type = 'merge-credit' AND biz_key = ? AND user_id = ?
        )
      `).bind(fromUserId, bizKey, toUserId),
    ]);
  }
}
