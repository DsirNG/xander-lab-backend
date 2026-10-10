export type PublishingPlatform = "csdn" | "juejin";

export interface PlatformCredentialRecord {
  userId: number;
  platform: PublishingPlatform;
  encryptedCredential: string;
  accountName: string | null;
  status: "ACTIVE" | "EXPIRED";
}

export interface PlatformCredentialRepository {
  find(userId: number, platform: PublishingPlatform): Promise<PlatformCredentialRecord | null>;
  save(input: {
    userId: number;
    platform: PublishingPlatform;
    encryptedCredential: string;
    accountName: string | null;
  }): Promise<void>;
  delete(userId: number, platform: PublishingPlatform): Promise<void>;
  markExpired(userId: number, platform: PublishingPlatform): Promise<void>;
}
