import type { ConnectablePlatform } from "./platform-connection.js";
import type { PlatformAccountProfile } from "./platform-account-port.js";

export type PublishingPlatform = "csdn" | "juejin";

export interface PlatformCredentialRecord {
  userId: number;
  platform: ConnectablePlatform;
  encryptedCredential: string;
  accountName: string | null;
  status: "ACTIVE" | "EXPIRED";
  authorizedAt?: string;
  lastValidatedAt?: string | null;
  accountProfile?: PlatformAccountProfile | null;
}

export interface PlatformCredentialRepository {
  list(userId: number): Promise<PlatformCredentialRecord[]>;
  find(userId: number, platform: ConnectablePlatform): Promise<PlatformCredentialRecord | null>;
  save(input: {
    userId: number;
    platform: ConnectablePlatform;
    encryptedCredential: string;
    accountName: string | null;
    accountProfile?: PlatformAccountProfile | null;
  }): Promise<void>;
  delete(userId: number, platform: ConnectablePlatform): Promise<void>;
  markExpired(userId: number, platform: ConnectablePlatform): Promise<void>;
  saveAccountProfile(userId: number, platform: PublishingPlatform, expectedCredential: string,
    profile: PlatformAccountProfile, verifiedAt: string): Promise<boolean>;
  saveWechatAccountProfile(userId: number, profile: PlatformAccountProfile, verifiedAt: string): Promise<boolean>;
}
