import type { PlatformExtensionAuthorizationRepository } from "../interfaces/platform-extension-authorization.js";
import type { ConnectablePlatform } from "../interfaces/platform-connection.js";
import { PlatformAuthorizationError } from "../interfaces/platform-authorization.js";
import { PlatformCredentialService } from "./platform-credential-service.js";

const TASK_TTL_SECONDS = 5 * 60;

/** Issues one-use handoff grants for the browser extension and stores harvested platform credentials. */
export class PlatformExtensionAuthorizationService {
  constructor(
    private readonly tasks: PlatformExtensionAuthorizationRepository,
    private readonly credentials: PlatformCredentialService,
    private readonly now: () => number = Date.now,
  ) {}

  async start(userId: number, platform: ConnectablePlatform) {
    if (!Number.isSafeInteger(userId) || userId <= 0) throw new PlatformAuthorizationError(400, "凭证属主参数非法");
    const token = this.randomToken();
    const expiresAt = new Date(this.now() + TASK_TTL_SECONDS * 1000).toISOString();
    const taskId = crypto.randomUUID();
    await this.tasks.create({
      id: taskId, userId, platform, tokenHash: await this.hash(token), status: "PENDING", expiresAt,
    });
    return { taskId, transferToken: token, expiresAt, expiresInSeconds: TASK_TTL_SECONDS };
  }

  async complete(taskId: string, platform: ConnectablePlatform, token: string, credential: unknown, accountName: string | null,
    accountProfile?: { nickname: string | null; avatarUrl: string | null } | null): Promise<void> {
    this.validateTaskId(taskId);
    if (!/^[A-Za-z0-9_-]{43}$/.test(token)) throw new PlatformAuthorizationError(401, "授权任务令牌无效或已过期");
    const now = new Date(this.now()).toISOString();
    const task = await this.tasks.find(taskId);
    if (!task || Date.parse(task.expiresAt) <= this.now()) {
      throw new PlatformAuthorizationError(410, "授权任务已过期，请重新发起");
    }
    if (task.platform !== platform) throw new PlatformAuthorizationError(404, "授权任务不存在");
    const tokenHash = await this.hash(token);
    if (tokenHash !== task.tokenHash) throw new PlatformAuthorizationError(401, "授权任务令牌无效或已过期");
    if (task.status === "COMPLETED") return;
    if (task.status !== "PENDING" || !await this.tasks.claim(taskId, tokenHash, now)) {
      throw new PlatformAuthorizationError(409, "授权任务已处理或已取消");
    }
    try {
      await this.credentials.save(task.userId, task.platform, credential, accountName,
        accountProfile ? { username: null, ...accountProfile } : null);
      await this.tasks.complete(taskId, new Date(this.now()).toISOString());
    } catch (error) {
      const current = await this.tasks.find(taskId);
      if (current?.status === "CANCELLED") {
        await this.credentials.delete(task.userId, task.platform);
        throw new PlatformAuthorizationError(409, "授权任务已处理或已取消");
      }
      await this.tasks.reopen(taskId, new Date(this.now()).toISOString());
      throw error;
    }
  }

  async cancel(userId: number, taskId: string): Promise<void> {
    this.validateTaskId(taskId);
    await this.tasks.cancel(taskId, userId);
  }

  cancelForPlatform(userId: number, platform: ConnectablePlatform): Promise<void> {
    return this.tasks.cancelForPlatform(userId, platform);
  }

  cleanupExpired(): Promise<void> {
    return this.tasks.expire(new Date(this.now()).toISOString());
  }

  private randomToken(): string {
    const bytes = crypto.getRandomValues(new Uint8Array(32));
    let binary = "";
    for (const value of bytes) binary += String.fromCharCode(value);
    return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/, "");
  }

  private async hash(token: string): Promise<string> {
    const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(token)));
    let binary = "";
    for (const value of digest) binary += String.fromCharCode(value);
    return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/, "");
  }

  private validateTaskId(taskId: string): void {
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(taskId)) {
      throw new PlatformAuthorizationError(400, "授权任务参数无效");
    }
  }
}
