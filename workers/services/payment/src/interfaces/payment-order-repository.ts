export type PaymentOrderStatus = "PENDING" | "DELIVERED" | "REFUNDED" | "FAILED";

export interface PaymentOrderRecord {
  id: number;
  createdAt?: string;
  kind?: "subscription" | "points";
  points?: number;
  outTradeNo: string;
  wxOrderId: string | null;
  userId: number;
  openid: string;
  productId: string;
  tier: "PLUS" | "PRO" | "ULTRA";
  goodsPrice: number;
  quantity: number;
  durationDays: number;
  status: PaymentOrderStatus;
  paidAt: string | null;
  deliveredAt: string | null;
}

export interface NewPaymentOrder {
  kind?: "subscription" | "points";
  points?: number;
  outTradeNo: string;
  userId: number;
  openid: string;
  productId: string;
  tier: "PLUS" | "PRO" | "ULTRA";
  goodsPrice: number;
  quantity: number;
  durationDays: number;
}

export interface PaymentOrderRepository {
  listOwned?(userId: number,before: number,limit: number): Promise<PaymentOrderRecord[]>;
  markRefunded?(id: number): Promise<void>;
  findPayable(userId: number, productId: string, quantity: number): Promise<PaymentOrderRecord | null>;
  insertPending(input: NewPaymentOrder): Promise<boolean>;
  findOwnedOrder(userId: number, outTradeNo: string): Promise<PaymentOrderRecord | null>;
  findOrder(outTradeNo: string): Promise<PaymentOrderRecord | null>;
  markDelivered(id: number, wxOrderId: string, paidAt?: string): Promise<boolean>;
}
