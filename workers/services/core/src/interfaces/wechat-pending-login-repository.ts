export interface WechatPendingLoginRepository {
  save(token: string, openid: string, ttlSeconds: number): Promise<void>;
  find(token: string): Promise<{ openid?: unknown } | null>;
  delete(token: string): Promise<void>;
}
