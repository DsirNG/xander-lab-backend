import type { D1Binding } from "@dinqor/worker-runtime";
import type { AgentWebSettingsRepository } from "../interfaces/agent-web-settings-repository.js";

/** Persists platform search settings in Agent D1 without exposing plaintext credentials. */
export class D1AgentWebSettingsMapper implements AgentWebSettingsRepository {
  constructor(private readonly db: D1Binding) {}
  async find() {
    const row = await this.db.prepare(`SELECT enabled, encrypted_api_key AS encryptedApiKey, updated_at AS updatedAt
      FROM agent_web_settings WHERE id = 1`).first<{ enabled: number; encryptedApiKey: string | null; updatedAt: string }>();
    return row ? { ...row, enabled: row.enabled === 1 } : null;
  }
  async save(input: { enabled: boolean; encryptedApiKey: string | null; updatedBy: number }): Promise<void> {
    await this.db.prepare(`INSERT INTO agent_web_settings (id, enabled, encrypted_api_key, updated_by)
      VALUES (1, ?, ?, ?) ON CONFLICT(id) DO UPDATE SET enabled = excluded.enabled,
      encrypted_api_key = excluded.encrypted_api_key, updated_by = excluded.updated_by,
      updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')`)
      .bind(input.enabled ? 1 : 0, input.encryptedApiKey, input.updatedBy).run();
  }
}
