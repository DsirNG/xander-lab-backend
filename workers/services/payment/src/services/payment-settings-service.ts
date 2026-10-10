import type {
  PaymentProduct,
  PaymentSettingsInput,
  PaymentSettingsRecord,
  PaymentSettingsRepository,
} from "../interfaces/payment-settings-repository.js";

const encoder = new TextEncoder();

export interface PaymentSettingsView {
  enabled: boolean;
  offerId: string;
  appKeyConfigured: boolean;
  plus: PaymentProduct;
  pro: PaymentProduct;
  ultra: PaymentProduct;
  updatedAt: string | null;
}

export interface CatalogProduct extends PaymentProduct {
  name: string;
  tier: "PLUS" | "PRO" | "ULTRA";
  goodsPrice: number;
}

export interface PaymentCatalog {
  enabled: boolean;
  products: CatalogProduct[];
}

export type PaymentSettingsSaveResult =
  | { kind: "saved"; view: PaymentSettingsView }
  | { kind: "invalid"; message: string }
  | { kind: "save_failed" };

export interface ResolvedPaymentSettings {
  offerId: string;
  appKey: string;
  products: CatalogProduct[];
}

function decodeBase64(value: string): Uint8Array | null {
  try {
    const binary = atob(value);
    return Uint8Array.from(binary, (character) => character.charCodeAt(0));
  } catch {
    return null;
  }
}

function encodeBase64(value: Uint8Array): string {
  let binary = "";
  for (const byte of value) binary += String.fromCharCode(byte);
  return btoa(binary);
}

function toArrayBuffer(value: Uint8Array): ArrayBuffer {
  const bytes = new Uint8Array(value.byteLength);
  bytes.set(value);
  return bytes.buffer;
}

async function importedKey(encoded: string | undefined): Promise<CryptoKey | null> {
  if (!encoded) return null;
  const bytes = decodeBase64(encoded.trim());
  if (!bytes || bytes.byteLength !== 32) return null;
  return crypto.subtle.importKey("raw", toArrayBuffer(bytes), "AES-GCM", false, ["encrypt", "decrypt"]);
}

async function encryptAppKey(value: string, encodedKey: string | undefined): Promise<string | null> {
  const key = await importedKey(encodedKey);
  if (!key) return null;
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const encrypted = await crypto.subtle.encrypt(
    { name: "AES-GCM", iv: toArrayBuffer(iv) }, key, toArrayBuffer(encoder.encode(value)),
  );
  return `${encodeBase64(iv)}.${encodeBase64(new Uint8Array(encrypted))}`;
}

async function decryptAppKey(value: string, encodedKey: string | undefined): Promise<string | null> {
  const [encodedIv, encodedData, extra] = value.split(".");
  const iv = encodedIv ? decodeBase64(encodedIv) : null;
  const data = encodedData ? decodeBase64(encodedData) : null;
  const key = await importedKey(encodedKey);
  if (!key || !iv || iv.byteLength !== 12 || !data || extra !== undefined) return null;
  try {
    const plaintext = await crypto.subtle.decrypt(
      { name: "AES-GCM", iv: toArrayBuffer(iv) }, key, toArrayBuffer(data),
    );
    return new TextDecoder("utf-8", { fatal: true }).decode(plaintext);
  } catch {
    return null;
  }
}

function productFromRow(row: PaymentSettingsRecord | null, tier: "plus" | "pro" | "ultra"): PaymentProduct {
  return {
    productId: String(row?.[`${tier}ProductId`] ?? ""),
    priceCents: Number(row?.[`${tier}PriceCents`] ?? 0),
    durationDays: Number(row?.[`${tier}DurationDays`] ?? 30),
  };
}

function view(row: PaymentSettingsRecord | null): PaymentSettingsView {
  return {
    enabled: Boolean(row?.enabled),
    offerId: row?.offerId ?? "",
    appKeyConfigured: Boolean(row?.encryptedAppKey),
    plus: productFromRow(row, "plus"),
    pro: productFromRow(row, "pro"),
    ultra: productFromRow(row, "ultra"),
    updatedAt: row?.updatedAt ?? null,
  };
}

function productRows(row: PaymentSettingsRecord | null): CatalogProduct[] {
  const products: CatalogProduct[] = [
    { ...productFromRow(row, "plus"), name: "PLUS月度订阅", tier: "PLUS", goodsPrice: Number(row?.plusPriceCents ?? 0) },
    { ...productFromRow(row, "pro"), name: "PRO月度订阅", tier: "PRO", goodsPrice: Number(row?.proPriceCents ?? 0) },
    { ...productFromRow(row, "ultra"), name: "ULTRA月度订阅", tier: "ULTRA", goodsPrice: Number(row?.ultraPriceCents ?? 0) },
  ];
  return products
    .filter((product) => product.productId.trim() !== "" && product.goodsPrice > 0
      && Number.isSafeInteger(product.goodsPrice) && product.durationDays > 0
      && Number.isSafeInteger(product.durationDays))
    .sort((left, right) => ["PLUS", "PRO", "ULTRA"].indexOf(left.tier)
      - ["PLUS", "PRO", "ULTRA"].indexOf(right.tier));
}

export class PaymentSettingsService {
  constructor(private readonly repository: PaymentSettingsRepository) {}

  async get(): Promise<PaymentSettingsView> {
    return view(await this.repository.findSettings());
  }

  async catalog(): Promise<PaymentCatalog> {
    const row = await this.repository.findSettings();
    const products = row && row.enabled && row.offerId && row.encryptedAppKey
      ? productRows(row)
      : [];
    return { enabled: products.length > 0, products };
  }

  async resolve(encryptionKey?: string): Promise<ResolvedPaymentSettings | null> {
    const row = await this.repository.findSettings();
    if (!row || !row.enabled || !row.offerId || !row.encryptedAppKey) return null;
    const appKey = await decryptAppKey(row.encryptedAppKey, encryptionKey);
    if (!appKey) return null;
    return { offerId: row.offerId, appKey, products: productRows(row) };
  }

  async save(
    input: Omit<PaymentSettingsInput, "encryptedAppKey"> & { appKey: string },
    encryptionKey?: string,
  ): Promise<PaymentSettingsSaveResult> {
    const existing = await this.repository.findSettings();
    let encryptedAppKey = existing?.encryptedAppKey ?? null;
    if (input.appKey) {
      encryptedAppKey = await encryptAppKey(input.appKey, encryptionKey);
      if (!encryptedAppKey) return { kind: "invalid", message: "微信加密密钥未配置" };
    }
    if (input.enabled) {
      if (!input.offerId) return { kind: "invalid", message: "OfferID 不能为空" };
      if (!encryptedAppKey) return { kind: "invalid", message: "AppKey 不能为空" };
      if ([input.plus, input.pro, input.ultra].some((product) => !product.productId
        || product.priceCents <= 0 || product.durationDays <= 0)) {
        return { kind: "invalid", message: "PLUS、PRO、ULTRA 商品配置必须完整" };
      }
    }
    const saveInput: PaymentSettingsInput = { ...input, encryptedAppKey };
    if (!await this.repository.saveSettings(saveInput)) return { kind: "save_failed" };
    return { kind: "saved", view: await this.get() };
  }
}
