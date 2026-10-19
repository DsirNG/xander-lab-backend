import type { AlipayProduct } from "./alipay-port.js";

export type WebOrderStatus = "PENDING" | "PAID" | "DELIVERED" | "CLOSED" | "REFUND_PENDING" | "REFUNDED";
export interface WebOrder extends AlipayProduct {
  outTradeNo: string;
  userId: number;
  idempotencyKey: string;
  appId: string;
  sellerId: string;
  environment: "sandbox" | "production";
  status: WebOrderStatus;
  tradeNo: string | null;
  deliveredAt: string | null;
}

export interface WebRefund {
  outRequestNo: string;
  outTradeNo: string;
  amountCents: number;
  status: "PENDING" | "SUCCEEDED";
  entitlementReviewRequired: number;
}

export interface AlipayOrderRepository {
  create(input: WebOrder): Promise<WebOrder>;
  find(outTradeNo: string): Promise<WebOrder | null>;
  markPaid(outTradeNo: string, tradeNo: string): Promise<void>;
  markDelivered(outTradeNo: string): Promise<void>;
  close(outTradeNo: string): Promise<void>;
  recordNotification(order: WebOrder, params: Record<string, string>): Promise<void>;
  reserveRefund(order: WebOrder): Promise<WebRefund>;
  findRefund(outTradeNo: string): Promise<WebRefund | null>;
  completeRefund(refund: WebRefund): Promise<void>;
}
