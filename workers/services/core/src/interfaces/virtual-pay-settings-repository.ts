export interface VirtualPayProduct {
  productId: string;
  priceCents: number;
  durationDays: number;
}

export interface VirtualPaySettingsRecord {
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

export interface VirtualPaySettingsInput {
  enabled: boolean;
  offerId: string;
  encryptedAppKey: string | null;
  plus: VirtualPayProduct;
  pro: VirtualPayProduct;
  ultra: VirtualPayProduct;
}

export interface VirtualPaySettingsRepository {
  findVirtualPaySettings(): Promise<VirtualPaySettingsRecord | null>;
  saveVirtualPaySettings(input: VirtualPaySettingsInput): Promise<boolean>;
}
