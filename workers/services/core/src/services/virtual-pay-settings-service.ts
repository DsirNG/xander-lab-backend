import type {
  VirtualPayProduct,
  VirtualPaySettingsInput,
  VirtualPaySettingsRecord,
  VirtualPaySettingsRepository,
} from "../interfaces/virtual-pay-settings-repository.js";
import { encryptSecret } from "./secret-service.js";

export interface VirtualPaySettingsView {
  enabled: boolean;
  offerId: string;
  appKeyConfigured: boolean;
  plus: VirtualPayProduct;
  pro: VirtualPayProduct;
  ultra: VirtualPayProduct;
  updatedAt: string | null;
}

export type VirtualPaySaveResult =
  | { kind: "saved"; view: VirtualPaySettingsView }
  | { kind: "invalid"; message: string }
  | { kind: "save_failed" };

function productFromRow(row: VirtualPaySettingsRecord | null, tier: "plus" | "pro" | "ultra") {
  return {
    productId: String(row?.[`${tier}ProductId`] ?? ""),
    priceCents: Number(row?.[`${tier}PriceCents`] ?? 0),
    durationDays: Number(row?.[`${tier}DurationDays`] ?? 30),
  };
}

function view(row: VirtualPaySettingsRecord | null): VirtualPaySettingsView {
  return row ? {
    enabled: Boolean(row.enabled),
    offerId: row.offerId,
    appKeyConfigured: Boolean(row.encryptedAppKey),
    plus: productFromRow(row, "plus"),
    pro: productFromRow(row, "pro"),
    ultra: productFromRow(row, "ultra"),
    updatedAt: row.updatedAt,
  } : {
    enabled: false,
    offerId: "",
    appKeyConfigured: false,
    plus: { productId: "", priceCents: 0, durationDays: 30 },
    pro: { productId: "", priceCents: 0, durationDays: 30 },
    ultra: { productId: "", priceCents: 0, durationDays: 30 },
    updatedAt: null,
  };
}

export class VirtualPaySettingsService {
  constructor(private readonly settings: VirtualPaySettingsRepository) {}

  async get(): Promise<VirtualPaySettingsView> {
    return view(await this.settings.findVirtualPaySettings());
  }

  async save(
    input: Omit<VirtualPaySettingsInput, "encryptedAppKey"> & { appKey: string },
    encryptionKey?: string,
  ): Promise<VirtualPaySaveResult> {
    const existing = await this.settings.findVirtualPaySettings();
    let encryptedAppKey = existing?.encryptedAppKey ?? null;
    if (input.appKey) {
      encryptedAppKey = await encryptSecret(input.appKey, encryptionKey);
      if (!encryptedAppKey) return { kind: "invalid", message: "微信加密密钥未配置" };
    }
    const products = [input.plus, input.pro, input.ultra];
    if (input.enabled) {
      if (!input.offerId) return { kind: "invalid", message: "OfferID 不能为空" };
      if (!encryptedAppKey) return { kind: "invalid", message: "AppKey 不能为空" };
      if (products.some((product) => !product.productId || product.priceCents <= 0 || product.durationDays <= 0)) {
        return { kind: "invalid", message: "PLUS、PRO、ULTRA 商品配置必须完整" };
      }
    }
    const saveInput: VirtualPaySettingsInput = {
      enabled: input.enabled,
      offerId: input.offerId,
      encryptedAppKey,
      plus: input.plus,
      pro: input.pro,
      ultra: input.ultra,
    };
    if (!await this.settings.saveVirtualPaySettings(saveInput)) return { kind: "save_failed" };
    return { kind: "saved", view: await this.get() };
  }
}
