export interface PaymentProduct {
  productId: string;
  priceCents: number;
  durationDays: number;
}

export interface PointsProduct {
  productId: string;
  name: string;
  priceCents: number;
  points: number;
  enabled: boolean;
}

export interface PaymentSettingsRecord {
  pointsProductsJson?: string | null;
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
  pointsProductId?: string;
  updatedAt: string | null;
}

export interface PaymentSettingsInput {
  pointsProducts?: PointsProduct[];
  enabled: boolean;
  offerId: string;
  encryptedAppKey: string | null;
  pointsProductId?: string;
  plus: PaymentProduct;
  pro: PaymentProduct;
  ultra: PaymentProduct;
}

export interface PaymentSettingsRepository {
  findSettings(): Promise<PaymentSettingsRecord | null>;
  saveSettings(input: PaymentSettingsInput): Promise<boolean>;
}
