import type { PaymentOrderRecord } from "../interfaces/payment-order-repository.js";

export const PAYMENT_WINDOW_MS = 15 * 60 * 1000;
export function paymentExpiresAt(order: PaymentOrderRecord): string | null {
  const created = Date.parse(order.createdAt ?? "");
  return Number.isFinite(created) ? new Date(created + PAYMENT_WINDOW_MS).toISOString() : null;
}
// Expiry closes local checkout, not verified provider delivery (callbacks may arrive late).
export function paymentStatus(order: PaymentOrderRecord, now = Date.now()) {
  if (order.status !== "PENDING") return order.status;
  const expires = paymentExpiresAt(order);
  return expires && Date.parse(expires) > now ? "PENDING" : "EXPIRED";
}
