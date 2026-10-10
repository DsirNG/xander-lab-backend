import type { D1Binding } from "@dinqor/worker-runtime";
import type {
  PaymentSettingsInput,
  PaymentSettingsRecord,
  PaymentSettingsRepository,
} from "../interfaces/payment-settings-repository.js";

const SELECT_SETTINGS = `
  SELECT enabled, offer_id AS offerId, encrypted_app_key AS encryptedAppKey,
         plus_product_id AS plusProductId, plus_price_cents AS plusPriceCents,
         plus_duration_days AS plusDurationDays, pro_product_id AS proProductId,
         pro_price_cents AS proPriceCents, pro_duration_days AS proDurationDays,
         ultra_product_id AS ultraProductId, ultra_price_cents AS ultraPriceCents,
         ultra_duration_days AS ultraDurationDays, updated_at AS updatedAt
  FROM virtual_pay_settings WHERE id = 1`;

export class D1PaymentSettingsMapper implements PaymentSettingsRepository {
  constructor(private readonly db: D1Binding) {}

  findSettings(): Promise<PaymentSettingsRecord | null> {
    return this.db.prepare(SELECT_SETTINGS).first<PaymentSettingsRecord>();
  }

  async saveSettings(input: PaymentSettingsInput): Promise<boolean> {
    const result = await this.db.prepare(`
      INSERT INTO virtual_pay_settings (
        id, enabled, offer_id, encrypted_app_key,
        plus_product_id, plus_price_cents, plus_duration_days,
        pro_product_id, pro_price_cents, pro_duration_days,
        ultra_product_id, ultra_price_cents, ultra_duration_days
      ) VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(id) DO UPDATE SET
        enabled = excluded.enabled,
        offer_id = excluded.offer_id,
        encrypted_app_key = excluded.encrypted_app_key,
        plus_product_id = excluded.plus_product_id,
        plus_price_cents = excluded.plus_price_cents,
        plus_duration_days = excluded.plus_duration_days,
        pro_product_id = excluded.pro_product_id,
        pro_price_cents = excluded.pro_price_cents,
        pro_duration_days = excluded.pro_duration_days,
        ultra_product_id = excluded.ultra_product_id,
        ultra_price_cents = excluded.ultra_price_cents,
        ultra_duration_days = excluded.ultra_duration_days,
        updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
    `).bind(
      input.enabled ? 1 : 0,
      input.offerId,
      input.encryptedAppKey,
      input.plus.productId,
      input.plus.priceCents,
      input.plus.durationDays,
      input.pro.productId,
      input.pro.priceCents,
      input.pro.durationDays,
      input.ultra.productId,
      input.ultra.priceCents,
      input.ultra.durationDays,
    ).run();
    return result.success;
  }
}
