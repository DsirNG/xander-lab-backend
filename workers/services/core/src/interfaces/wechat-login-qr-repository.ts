export interface WechatLoginQrRepository {
  createLoginQr(ticket: string): Promise<string | null>;
}
