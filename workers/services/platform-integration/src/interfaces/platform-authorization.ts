import type { PublishingPlatform } from "./platform-credential-repository.js";

export class PlatformAuthorizationError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
    this.name = "PlatformAuthorizationError";
  }
}

export interface PlatformAuthorizationSlot {
  platform: PublishingPlatform;
  userId: number;
  encryptedSessionId: string | null;
  startingAt: string | null;
  expiresAt: string;
}

export interface PlatformAuthorizationQueueEntry {
  platform: PublishingPlatform;
  userId: number;
  queuePosition: number;
  estimatedWaitSeconds: number;
}

export interface PlatformAuthorizationRepository {
  findSlot(platform: PublishingPlatform): Promise<PlatformAuthorizationSlot | null>;
  tryAcquireSlot(platform: PublishingPlatform, userId: number, expiresAt: string): Promise<boolean>;
  tryMarkStarting(platform: PublishingPlatform, userId: number): Promise<boolean>;
  saveSessionId(platform: PublishingPlatform, userId: number, encryptedSessionId: string): Promise<boolean>;
  deleteSlot(platform: PublishingPlatform, userId: number): Promise<void>;
  enqueue(platform: PublishingPlatform, userId: number, expiresAt: string): Promise<void>;
  findQueueEntry(platform: PublishingPlatform, userId: number): Promise<PlatformAuthorizationQueueEntry | null>;
  claimNext(platform: PublishingPlatform, expiresAt: string): Promise<number | null>;
  deleteQueueEntry(platform: PublishingPlatform, userId: number): Promise<void>;
  removeExpired(now: string): Promise<PlatformAuthorizationSlot[]>;
}

export interface PlatformBrowserPort {
  start(platform: PublishingPlatform): Promise<{ sessionId: string; qrCodeDataUrl: string | null }>;
  qrCode(platform: PublishingPlatform, sessionId: string): Promise<string | null>;
  cookies(sessionId: string): Promise<Array<{ name: string; value: string }>>;
  close(sessionId: string): Promise<void>;
}

export interface PlatformAuthorizationView {
  status: "AUTHORIZED" | "EXPIRED" | "NOT_AUTHORIZED" | "PENDING" | "QUEUED";
  qrCodeDataUrl: string | null;
  expiresInSeconds: number | null;
  queuePosition: number | null;
  estimatedWaitSeconds: number | null;
}
