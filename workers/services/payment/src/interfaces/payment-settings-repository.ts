export interface PaymentProduct {
  productId: string;
  priceCents: number;
  durationDays: number;
}

export interface PaymentSettingsRecord {
  enabled: number;
  offerId: string;
  encryptedAppKey: string | null;
  plusProductId: string;
  plusPriceCents: number;
  plusDurationDays: number;
  proProductId: string;
  proPriceCents: number;
  proDurationDays: number;
  ultraProductId: string;
  ultraPriceCents: number;
  ultraDurationDays: number;
  updatedAt: string | null;
}

export interface PaymentSettingsInput {
  enabled: boolean;
  offerId: string;
  encryptedAppKey: string | null;
  plus: PaymentProduct;
  pro: PaymentProduct;
  ultra: PaymentProduct;
}

export interface PaymentSettingsRepository {
  findSettings(): Promise<PaymentSettingsRecord | null>;
  saveSettings(input: PaymentSettingsInput): Promise<boolean>;
}
