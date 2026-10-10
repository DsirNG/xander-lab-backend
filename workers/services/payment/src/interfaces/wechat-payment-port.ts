export interface WechatOrderQueryResult {
  paid: boolean;
  wxOrderId: string | null;
  paidFee: number;
}

export interface WechatPaymentPort {
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
