import type { D1Binding } from "@dinqor/worker-runtime";
import type {
  PlatformCredentialRecord,
  PlatformCredentialRepository,
} from "../interfaces/platform-credential-repository.js";
import type { ConnectablePlatform } from "../interfaces/platform-connection.js";
import type { PlatformAccountProfile } from "../interfaces/platform-account-port.js";

type CredentialRow = Omit<PlatformCredentialRecord, "accountProfile"> & { accountProfileJson?: string | null };

function credentialRecord(row: CredentialRow): PlatformCredentialRecord {
  const { accountProfileJson, ...record } = row;
  return { ...record, accountProfile: accountProfileJson ? JSON.parse(accountProfileJson) as PlatformAccountProfile : null };
}

export class D1PlatformCredentialMapper implements PlatformCredentialRepository {
  constructor(private readonly db: D1Binding) {}

  list(userId: number): Promise<PlatformCredentialRecord[]> {
    return this.db.prepare(`
      SELECT user_id AS userId, platform, encrypted_credential AS encryptedCredential,
             account_name AS accountName, status, authorized_at AS authorizedAt,
             last_validated_at AS lastValidatedAt, account_profile AS accountProfileJson
        FROM platform_credential
       WHERE user_id = ?
    `).bind(userId).all<CredentialRow>().then((result) => (result.results ?? []).map(credentialRecord));
  }

  find(userId: number, platform: ConnectablePlatform): Promise<PlatformCredentialRecord | null> {
    return this.db.prepare(`
      SELECT user_id AS userId, platform, encrypted_credential AS encryptedCredential,
             account_name AS accountName, status, authorized_at AS authorizedAt,
             last_validated_at AS lastValidatedAt, account_profile AS accountProfileJson
        FROM platform_credential
       WHERE user_id = ? AND platform = ?
    `).bind(userId, platform).first<CredentialRow>().then((row) => row ? credentialRecord(row) : null);
  }

  async save(input: {
    userId: number;
    platform: ConnectablePlatform;
    encryptedCredential: string;
    accountName: string | null;
    accountProfile?: PlatformAccountProfile | null;
  }): Promise<void> {
    const result = await this.db.prepare(`
      INSERT INTO platform_credential (
        user_id, platform, encrypted_credential, account_name, status,
        authorized_at, last_validated_at, account_profile, updated_at
      ) VALUES (?, ?, ?, ?, 'ACTIVE', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'),
                CASE WHEN ? IS NULL THEN NULL ELSE strftime('%Y-%m-%dT%H:%M:%fZ', 'now') END, ?, strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
      ON CONFLICT(user_id, platform) DO UPDATE SET
        encrypted_credential = excluded.encrypted_credential,
        account_name = excluded.account_name,
        account_profile = excluded.account_profile,
        status = 'ACTIVE',
        authorized_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now'),
        last_validated_at = excluded.last_validated_at,
        updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
    `).bind(input.userId, input.platform, input.encryptedCredential, input.accountName,
      input.accountProfile ? 1 : null, input.accountProfile ? JSON.stringify(input.accountProfile) : null).run();
    if (!result.success) throw new Error("platform credential save failed");
  }

  async delete(userId: number, platform: ConnectablePlatform): Promise<void> {
    const result = await this.db.prepare(
      "DELETE FROM platform_credential WHERE user_id = ? AND platform = ?",
    ).bind(userId, platform).run();
    if (!result.success) throw new Error("platform credential delete failed");
  }

  async markExpired(userId: number, platform: ConnectablePlatform): Promise<void> {
    const result = await this.db.prepare(`
      UPDATE platform_credential
         SET status = 'EXPIRED', updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
       WHERE user_id = ? AND platform = ? AND status = 'ACTIVE'
    `).bind(userId, platform).run();
    if (!result.success) throw new Error("platform credential expiry update failed");
  }

  async saveAccountProfile(userId: number, platform: "csdn" | "juejin", expectedCredential: string,
    profile: PlatformAccountProfile, verifiedAt: string): Promise<boolean> {
    // Fence against disconnect or reauthorization while the provider request was in flight.
    const result = await this.db.prepare(`
      UPDATE platform_credential SET account_profile = ?, last_validated_at = ?, updated_at = ?
       WHERE user_id = ? AND platform = ? AND status = 'ACTIVE' AND encrypted_credential = ?
    `).bind(JSON.stringify(profile), verifiedAt, verifiedAt, userId, platform, expectedCredential).run();
    if (!result.success) throw new Error("platform account profile save failed");
    return result.meta?.changes === 1;
  }
}
