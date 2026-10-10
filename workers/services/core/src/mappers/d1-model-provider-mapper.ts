import type { D1Binding } from "@dinqor/worker-runtime";
import type {
  ModelProviderInput,
  ModelProviderRecord,
  ModelProviderRepository,
} from "../interfaces/model-provider-repository.js";

const PROVIDER_SELECT = `
  SELECT id, name, vendor, base_url AS baseUrl, encrypted_api_key AS encryptedApiKey,
         description, enabled, priority, created_at AS createdAt, updated_at AS updatedAt
  FROM model_provider`;

export class D1ModelProviderMapper implements ModelProviderRepository {
  constructor(private readonly db: D1Binding) {}

  async listProviders(): Promise<ModelProviderRecord[]> {
    const result = await this.db.prepare(`${PROVIDER_SELECT} ORDER BY priority, id`)
      .all<ModelProviderRecord>();
    return result.results ?? [];
  }

  findProvider(id: number): Promise<ModelProviderRecord | null> {
    return this.db.prepare(`${PROVIDER_SELECT} WHERE id = ?`).bind(id).first<ModelProviderRecord>();
  }

  async hasProviderName(name: string, exceptId?: number): Promise<boolean> {
    const query = exceptId === undefined
      ? "SELECT id FROM model_provider WHERE name = ? COLLATE NOCASE"
      : "SELECT id FROM model_provider WHERE name = ? COLLATE NOCASE AND id <> ?";
    const row = await this.db.prepare(query)
      .bind(...(exceptId === undefined ? [name] : [name, exceptId]))
      .first<{ id: number }>();
    return row !== null;
  }

  createProvider(input: ModelProviderInput): Promise<ModelProviderRecord | null> {
    return this.db.prepare(`
      INSERT INTO model_provider (name, vendor, base_url, encrypted_api_key, description, enabled, priority)
      VALUES (?, ?, ?, ?, ?, ?, ?)
      RETURNING id, name, vendor, base_url AS baseUrl, encrypted_api_key AS encryptedApiKey,
                description, enabled, priority, created_at AS createdAt, updated_at AS updatedAt
    `).bind(input.name, input.vendor, input.baseUrl, input.encryptedApiKey, input.description,
      input.enabled ? 1 : 0, input.priority).first<ModelProviderRecord>();
  }

  updateProvider(id: number, input: ModelProviderInput): Promise<ModelProviderRecord | null> {
    return this.db.prepare(`
      UPDATE model_provider
      SET name = ?, vendor = ?, base_url = ?, encrypted_api_key = ?, description = ?, enabled = ?, priority = ?,
          updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
      WHERE id = ?
      RETURNING id, name, vendor, base_url AS baseUrl, encrypted_api_key AS encryptedApiKey,
                description, enabled, priority, created_at AS createdAt, updated_at AS updatedAt
    `).bind(input.name, input.vendor, input.baseUrl, input.encryptedApiKey, input.description,
      input.enabled ? 1 : 0, input.priority, id).first<ModelProviderRecord>();
  }

  async setProviderEnabled(id: number, enabled: boolean): Promise<void> {
    await this.db.prepare(`
      UPDATE model_provider SET enabled = ?, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
      WHERE id = ?
    `).bind(enabled ? 1 : 0, id).run();
  }

  async countFeatureReferences(id: number): Promise<number> {
    const row = await this.db.prepare(`
      SELECT COUNT(*) AS total FROM feature_model_config
      WHERE primary_provider_id = ? OR fallback_provider_id = ?
    `).bind(id, id).first<{ total: number }>();
    return row?.total ?? 0;
  }

  async deleteProvider(id: number): Promise<void> {
    await this.db.prepare("DELETE FROM model_provider WHERE id = ?").bind(id).run();
  }
}
