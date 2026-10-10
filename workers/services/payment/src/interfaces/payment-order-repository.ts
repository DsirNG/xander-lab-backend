export type PaymentOrderStatus = "PENDING" | "DELIVERED" | "REFUNDED" | "FAILED";

export interface PaymentOrderRecord {
  id: number;
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
  insertPending(input: NewPaymentOrder): Promise<boolean>;
  findOwnedOrder(userId: number, outTradeNo: string): Promise<PaymentOrderRecord | null>;
  findOrder(outTradeNo: string): Promise<PaymentOrderRecord | null>;
  markDelivered(id: number, wxOrderId: string): Promise<boolean>;
}
