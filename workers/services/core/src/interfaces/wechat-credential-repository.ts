export interface StoredWechatCredential {
  appId: string;
  encryptedAppSecret: string;
  updatedAt: string;
}

export interface WechatCredentialRepository {
  findWechatCredential(): Promise<StoredWechatCredential | null>;
  saveWechatCredential(appId: string, encryptedAppSecret: string): Promise<StoredWechatCredential | null>;
  deleteWechatCredential(): Promise<void>;
}
