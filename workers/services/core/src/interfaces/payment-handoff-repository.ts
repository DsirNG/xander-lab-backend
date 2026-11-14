/** Short-lived navigation intent; this record never authorizes payment or grants benefits. */
export interface PaymentHandoff extends Record<string, unknown> {
  userId: number;
  productId: string;
  quantity: number;
  outTradeNo: string | null;
  status: "WAITING" | "ORDER_CREATED";
  expiresAt: number;
}

export interface PaymentHandoffRepository {
  save(ticket: string, state: PaymentHandoff, ttl: number): Promise<void>;
  find(ticket: string): Promise<PaymentHandoff | null>;
  attach(ticket: string, outTradeNo: string, ttl: number): Promise<void>;
  delete(ticket: string): Promise<void>;
}
