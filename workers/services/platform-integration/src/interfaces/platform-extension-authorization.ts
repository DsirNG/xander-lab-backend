import type { ConnectablePlatform } from "./platform-connection.js";

export type PlatformExtensionAuthorizationStatus = "PENDING" | "PROCESSING" | "COMPLETED" | "CANCELLED" | "EXPIRED";

export interface PlatformExtensionAuthorizationTask {
  id: string;
  userId: number;
  platform: ConnectablePlatform;
  tokenHash: string;
  status: PlatformExtensionAuthorizationStatus;
  expiresAt: string;
}

export interface PlatformExtensionAuthorizationRepository {
  create(task: PlatformExtensionAuthorizationTask): Promise<void>;
  find(id: string): Promise<PlatformExtensionAuthorizationTask | null>;
  claim(taskId: string, tokenHash: string, now: string): Promise<boolean>;
  complete(taskId: string, now: string): Promise<void>;
  reopen(taskId: string, now: string): Promise<void>;
  cancel(taskId: string, userId: number): Promise<void>;
  cancelForPlatform(userId: number, platform: ConnectablePlatform): Promise<void>;
  expire(now: string): Promise<void>;
}
