export interface WechatOrderQueryResult {
  status?: number;
  orderType?: number;
  leftFee?: number;
  paidTime?: number;
  paid: boolean;
  wxOrderId: string | null;
  paidFee: number;
}

export interface WechatRefundRequest { openid:string; order_id:string; refund_order_id:string; left_fee:number; refund_fee:number; biz_meta:string; refund_reason:string; req_from:string; env:number }
export interface WechatPaymentPort {
  refundOrder?(input: WechatRefundRequest,appKey:string): Promise<{accepted:boolean; wxRefundId:string|null; code:number}>;
  queryOrder(openid: string, outTradeNo: string, appKey: string): Promise<WechatOrderQueryResult>;
}

export interface WechatDeliveryNotification {
  event: string;
  openid: string | null;
  outTradeNo: string | null;
  wxOrderId: string | null;
  productId: string | null;
  quantity: number;
  env: number;
}
