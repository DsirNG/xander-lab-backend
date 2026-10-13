import type { D1Binding } from "@dinqor/worker-runtime";
import type {
  PlatformExtensionAuthorizationRepository,
  PlatformExtensionAuthorizationTask,
} from "../interfaces/platform-extension-authorization.js";

/** Persists short-lived, user-owned grants used by the browser extension handoff. */
export class D1PlatformExtensionAuthorizationMapper implements PlatformExtensionAuthorizationRepository {
  constructor(private readonly db: D1Binding) {}

  async create(task: PlatformExtensionAuthorizationTask): Promise<void> {
    const result = await this.db.prepare(`
      INSERT INTO platform_extension_authorization
        (id, user_id, platform, token_hash, status, expires_at)
      VALUES (?, ?, ?, ?, 'PENDING', ?)
    `).bind(task.id, task.userId, task.platform, task.tokenHash, task.expiresAt).run();
    if (!result.success) throw new Error("platform extension authorization creation failed");
  }

  find(id: string): Promise<PlatformExtensionAuthorizationTask | null> {
    return this.db.prepare(`
      SELECT id, user_id AS userId, platform, token_hash AS tokenHash, status, expires_at AS expiresAt
      FROM platform_extension_authorization WHERE id = ?
    `).bind(id).first<PlatformExtensionAuthorizationTask>();
  }

  async claim(taskId: string, tokenHash: string, now: string): Promise<boolean> {
    const result = await this.db.prepare(`
      UPDATE platform_extension_authorization SET status = 'PROCESSING'
      WHERE id = ? AND token_hash = ? AND status = 'PENDING' AND expires_at > ?
    `).bind(taskId, tokenHash, now).run();
    return result.success && (result.meta?.changes ?? 0) === 1;
  }

  async complete(taskId: string, now: string): Promise<void> {
    const result = await this.db.prepare(`
      UPDATE platform_extension_authorization SET status = 'COMPLETED', completed_at = ?
      WHERE id = ? AND status = 'PROCESSING'
    `).bind(now, taskId).run();
    if (!result.success || result.meta?.changes !== 1) throw new Error("platform extension authorization completion failed");
  }

  async reopen(taskId: string, now: string): Promise<void> {
    await this.db.prepare(`
      UPDATE platform_extension_authorization SET status = 'PENDING'
      WHERE id = ? AND status = 'PROCESSING' AND expires_at > ?
    `).bind(taskId, now).run();
  }

  async cancel(taskId: string, userId: number): Promise<void> {
    await this.db.prepare(`
      UPDATE platform_extension_authorization SET status = 'CANCELLED'
      WHERE id = ? AND user_id = ? AND status = 'PENDING'
    `).bind(taskId, userId).run();
  }

  async cancelForPlatform(userId: number, platform: PlatformExtensionAuthorizationTask["platform"]): Promise<void> {
    await this.db.prepare(`
      UPDATE platform_extension_authorization SET status = 'CANCELLED'
      WHERE user_id = ? AND platform = ? AND status IN ('PENDING', 'PROCESSING')
    `).bind(userId, platform).run();
  }

  async expire(now: string): Promise<void> {
    await this.db.prepare(`
      UPDATE platform_extension_authorization SET status = 'EXPIRED'
      WHERE status IN ('PENDING', 'PROCESSING') AND expires_at <= ?
    `).bind(now).run();
    await this.db.prepare("DELETE FROM platform_extension_authorization WHERE expires_at <= ?").bind(now).run();
  }
}
