export interface PaymentRefund {
  external?: number;
  refundId: string;
  outTradeNo: string;
  status:
    | "PREPARING"
    | "SUBMITTING"
    | "PROCESSING"
    | "REVIEW"
    | "SUCCEEDED"
    | "FAILED";
  refundFee: number;
  leftFee: number;
  pointsAmount: number;
  wxRefundId: string | null;
}
export interface PaymentRefundRepository {
  find(outTradeNo: string): Promise<PaymentRefund | null>;
  create(
    outTradeNo: string,
    refundId: string,
    expectedAmount: number,
    external?: boolean,
  ): Promise<void>;
  claim(
    outTradeNo: string,
    refundFee: number,
    leftFee: number,
    pointsAmount: number,
  ): Promise<boolean>;
  update(
    outTradeNo: string,
    status: PaymentRefund["status"],
    wxRefundId?: string,
  ): Promise<void>;
  touch(outTradeNo: string): Promise<void>;
  pending(limit: number): Promise<string[]>;
}
