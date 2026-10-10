import type { D1Binding } from "@dinqor/worker-runtime";
import type {
  PlatformCredentialRecord,
  PlatformCredentialRepository,
  PublishingPlatform,
} from "../interfaces/platform-credential-repository.js";

export class D1PlatformCredentialMapper implements PlatformCredentialRepository {
  constructor(private readonly db: D1Binding) {}

  find(userId: number, platform: PublishingPlatform): Promise<PlatformCredentialRecord | null> {
    return this.db.prepare(`
      SELECT user_id AS userId, platform, encrypted_credential AS encryptedCredential,
             account_name AS accountName, status
        FROM platform_credential
       WHERE user_id = ? AND platform = ?
    `).bind(userId, platform).first<PlatformCredentialRecord>();
  }

  async save(input: {
    userId: number;
    platform: PublishingPlatform;
    encryptedCredential: string;
    accountName: string | null;
  }): Promise<void> {
    const result = await this.db.prepare(`
      INSERT INTO platform_credential (
        user_id, platform, encrypted_credential, account_name, status,
        authorized_at, last_validated_at, updated_at
      ) VALUES (?, ?, ?, ?, 'ACTIVE', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'),
                strftime('%Y-%m-%dT%H:%M:%fZ', 'now'), strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
      ON CONFLICT(user_id, platform) DO UPDATE SET
        encrypted_credential = excluded.encrypted_credential,
        account_name = excluded.account_name,
        status = 'ACTIVE',
        authorized_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now'),
        last_validated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now'),
        updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
    `).bind(input.userId, input.platform, input.encryptedCredential, input.accountName).run();
    if (!result.success) throw new Error("platform credential save failed");
  }

  async delete(userId: number, platform: PublishingPlatform): Promise<void> {
    const result = await this.db.prepare(
      "DELETE FROM platform_credential WHERE user_id = ? AND platform = ?",
    ).bind(userId, platform).run();
    if (!result.success) throw new Error("platform credential delete failed");
  }

  async markExpired(userId: number, platform: PublishingPlatform): Promise<void> {
    const result = await this.db.prepare(`
      UPDATE platform_credential
         SET status = 'EXPIRED', updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
       WHERE user_id = ? AND platform = ? AND status = 'ACTIVE'
    `).bind(userId, platform).run();
    if (!result.success) throw new Error("platform credential expiry update failed");
  }
}
