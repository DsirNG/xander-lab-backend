import type {
  PlatformAuthorizationRepository,
  PlatformAuthorizationSlot,
  PlatformAuthorizationView,
  PlatformBrowserPort,
} from "../interfaces/platform-authorization.js";
import { PlatformAuthorizationError } from "../interfaces/platform-authorization.js";
import type { PublishingPlatform } from "../interfaces/platform-credential-repository.js";
import { PlatformCredentialService } from "./platform-credential-service.js";

const QUEUE_REFRESH_SECONDS = 300;

export { PlatformAuthorizationError };

/** Coordinates owner-scoped QR sessions, D1 leases, FIFO promotion and credential harvest. */
export class PlatformAuthorizationService {
  constructor(
    private readonly repository: PlatformAuthorizationRepository,
    private readonly browser: PlatformBrowserPort,
    private readonly credentials: PlatformCredentialService,
    private readonly timeoutSeconds: Record<PublishingPlatform, number> = { csdn: 35, juejin: 60 },
    private readonly now: () => number = Date.now,
  ) {}

  async start(userId: number, platform: PublishingPlatform): Promise<PlatformAuthorizationView> {
    this.validateOwner(userId);
    await this.expireSessions();
    let slot = await this.repository.findSlot(platform);
    if (slot?.userId === userId) return this.startBrowser(platform, userId, slot);
    if (slot) {
      await this.repository.enqueue(platform, userId, this.expiry(platform));
      return this.queued(platform, userId);
    }

    const queued = await this.repository.claimNext(platform, this.expiry(platform));
    if (queued !== null) {
      slot = await this.repository.findSlot(platform);
      if (slot?.userId === userId) return this.startBrowser(platform, userId, slot);
      if (slot) await this.ensureBrowserStarted(platform, slot);
      await this.repository.enqueue(platform, userId, this.expiry(platform));
      return this.queued(platform, userId);
    }

    if (!await this.repository.tryAcquireSlot(platform, userId, this.expiry(platform))) {
      slot = await this.repository.findSlot(platform);
      if (slot && slot.userId !== userId) {
        await this.repository.enqueue(platform, userId, this.expiry(platform));
        return this.queued(platform, userId);
      }
      if (slot) return this.pending(platform, slot);
      throw new PlatformAuthorizationError(503, "无法取得平台授权会话");
    }
    slot = await this.repository.findSlot(platform);
    if (!slot || slot.userId !== userId) throw new PlatformAuthorizationError(503, "平台授权会话状态异常");
    return this.startBrowser(platform, userId, slot);
  }

  async status(userId: number, platform: PublishingPlatform): Promise<PlatformAuthorizationView> {
    this.validateOwner(userId);
    await this.expireSessions();
    let slot = await this.repository.findSlot(platform);
    if (!slot) {
      const queued = await this.repository.findQueueEntry(platform, userId);
      if (queued?.queuePosition === 1) {
        const next = await this.repository.claimNext(platform, this.expiry(platform));
        if (next !== null) slot = await this.repository.findSlot(platform);
      }
      if (!slot) {
        const latest = await this.repository.findQueueEntry(platform, userId);
        if (latest) return this.refreshQueue(platform, userId);
        return this.credentials.status(userId, platform);
      }
    }
    if (slot.userId !== userId) {
      const queued = await this.repository.findQueueEntry(platform, userId);
      return queued ? this.refreshQueue(platform, userId) : this.credentials.status(userId, platform);
    }
    if (!slot.encryptedSessionId) return this.startBrowser(platform, userId, slot);

    const sessionId = await this.credentials.revealAuthorizationSession(userId, platform, slot.encryptedSessionId);
    const cookies = await this.browser.cookies(sessionId);
    const harvested = await this.harvest(userId, platform, cookies);
    if (harvested) {
      await this.credentials.save(userId, platform, harvested.credential, harvested.accountName);
      await this.releaseSlot(platform, userId, sessionId);
      return this.view("AUTHORIZED", null, null);
    }
    const qr = await this.browser.qrCode(platform, sessionId);
    return this.view("PENDING", qr, slot.expiresAt);
  }

  async cancel(userId: number, platform: PublishingPlatform): Promise<void> {
    this.validateOwner(userId);
    await this.repository.deleteQueueEntry(platform, userId);
    const slot = await this.repository.findSlot(platform);
    if (!slot || slot.userId !== userId) return;
    const sessionId = slot.encryptedSessionId
      ? await this.credentials.revealAuthorizationSession(userId, platform, slot.encryptedSessionId).catch(() => null)
      : null;
    await this.repository.deleteSlot(platform, userId);
    if (sessionId) await this.browser.close(sessionId);
  }

  async disconnect(userId: number, platform: PublishingPlatform): Promise<void> {
    await this.cancel(userId, platform);
    await this.credentials.delete(userId, platform);
  }

  async cleanupExpired(): Promise<void> { await this.expireSessions(); }

  private async startBrowser(platform: PublishingPlatform, userId: number, slot: PlatformAuthorizationSlot) {
    if (!slot.encryptedSessionId && !await this.repository.tryMarkStarting(platform, userId)) {
      return this.view("PENDING", null, slot.expiresAt);
    }
    if (slot.encryptedSessionId) return this.pending(platform, slot);
    let sessionId: string | null = null;
    try {
      const browser = await this.browser.start(platform);
      sessionId = browser.sessionId;
      const encrypted = await this.credentials.protectAuthorizationSession(userId, platform, browser.sessionId);
      if (!await this.repository.saveSessionId(platform, userId, encrypted)) {
        await this.browser.close(browser.sessionId);
        throw new PlatformAuthorizationError(409, "平台授权会话已取消");
      }
      return this.view("PENDING", browser.qrCodeDataUrl, slot.expiresAt);
    } catch (error) {
      await this.repository.deleteSlot(platform, userId);
      if (sessionId) await this.browser.close(sessionId);
      throw error;
    }
  }

  private async ensureBrowserStarted(platform: PublishingPlatform, slot: PlatformAuthorizationSlot): Promise<void> {
    if (slot.encryptedSessionId) return;
    await this.startBrowser(platform, slot.userId, slot);
  }

  private async pending(platform: PublishingPlatform, slot: PlatformAuthorizationSlot): Promise<PlatformAuthorizationView> {
    if (!slot.encryptedSessionId) return this.view("PENDING", null, slot.expiresAt);
    const sessionId = await this.credentials.revealAuthorizationSession(slot.userId, platform, slot.encryptedSessionId);
    return this.view("PENDING", await this.browser.qrCode(platform, sessionId), slot.expiresAt);
  }

  private async queued(platform: PublishingPlatform, userId: number): Promise<PlatformAuthorizationView> {
    const entry = await this.repository.findQueueEntry(platform, userId);
    if (entry) return this.refreshQueue(platform, userId);
    const slot = await this.repository.findSlot(platform);
    return slot?.userId === userId ? this.pending(platform, slot) : this.credentials.status(userId, platform);
  }

  private async refreshQueue(platform: PublishingPlatform, userId: number): Promise<PlatformAuthorizationView> {
    await this.repository.enqueue(platform, userId, this.expiry(platform));
    const entry = await this.repository.findQueueEntry(platform, userId);
    return entry ? this.queueView(entry) : this.credentials.status(userId, platform);
  }

  private queueView(entry: { queuePosition: number; estimatedWaitSeconds: number }): PlatformAuthorizationView {
    return {
      status: "QUEUED", qrCodeDataUrl: null, expiresInSeconds: null,
      queuePosition: entry.queuePosition, estimatedWaitSeconds: entry.estimatedWaitSeconds,
    };
  }

  private async expireSessions(): Promise<void> {
    const expired = await this.repository.removeExpired(new Date(this.now()).toISOString());
    await Promise.all(expired.flatMap((slot) => slot.encryptedSessionId
      ? [this.credentials.revealAuthorizationSession(slot.userId, slot.platform, slot.encryptedSessionId)
        .then((sessionId) => this.browser.close(sessionId)).catch(() => undefined)] : []));
  }

  private async releaseSlot(platform: PublishingPlatform, userId: number, sessionId: string): Promise<void> {
    await this.repository.deleteSlot(platform, userId);
    await this.browser.close(sessionId);
  }

  private async harvest(userId: number, platform: PublishingPlatform, cookies: Array<{ name: string; value: string }>) {
    const value = (name: string) => cookies.find((cookie) => cookie.name === name)?.value ?? null;
    const cookie = [...cookies].sort((a, b) => a.name < b.name ? -1 : a.name > b.name ? 1 : 0)
      .map((entry) => `${entry.name}=${entry.value}`).join("; ");
    if (platform === "csdn") {
      const token = value("UserToken");
      if (!token) return null;
      const username = value("UserName");
      return { credential: { cookie, username: username ?? "" }, accountName: username };
    }
    const sessionKey = value("sessionid_ss") || value("sessionid") || value("sid_tt");
    if (!sessionKey) return null;
    return {
      credential: {
        cookie, csrfToken: value("passport_csrf_token") ?? "", uuid: value("uuid") ?? "",
        sessionKey,
      },
      accountName: value("UserName"),
    };
  }

  private view(status: PlatformAuthorizationView["status"], qrCodeDataUrl: string | null, expiresAt: string | null): PlatformAuthorizationView {
    return {
      status, qrCodeDataUrl,
      expiresInSeconds: expiresAt ? Math.max(0, Math.ceil((Date.parse(expiresAt) - this.now()) / 1000)) : null,
      queuePosition: null, estimatedWaitSeconds: null,
    };
  }

  private expiry(platform: PublishingPlatform): string {
    return new Date(this.now() + this.timeout(platform) * 1000).toISOString();
  }

  private timeout(platform: PublishingPlatform): number {
    const value = this.timeoutSeconds[platform];
    return Number.isSafeInteger(value) && value >= 15 && value <= 600 ? value : (platform === "csdn" ? 35 : 60);
  }

  private validateOwner(userId: number): void {
    if (!Number.isSafeInteger(userId) || userId <= 0) throw new PlatformAuthorizationError(400, "凭证属主参数非法");
  }
}
