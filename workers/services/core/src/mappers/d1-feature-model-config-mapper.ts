import type { D1Binding } from "@dinqor/worker-runtime";
import type {
  FeatureModelConfigInput,
  FeatureModelConfigRecord,
  FeatureModelConfigRepository,
} from "../interfaces/feature-model-config-repository.js";

const FEATURE_SELECT = `
  SELECT feature_key AS featureKey, enabled, primary_provider_id AS primaryProviderId,
         primary_model AS primaryModel, primary_api_style AS primaryApiStyle,
         fallback_provider_id AS fallbackProviderId, fallback_model AS fallbackModel,
         fallback_api_style AS fallbackApiStyle, updated_at AS updatedAt
  FROM feature_model_config`;

export class D1FeatureModelConfigMapper implements FeatureModelConfigRepository {
  constructor(private readonly db: D1Binding) {}

  async listFeatureConfigs(): Promise<FeatureModelConfigRecord[]> {
    const result = await this.db.prepare(FEATURE_SELECT).all<FeatureModelConfigRecord>();
    return result.results ?? [];
  }

  findFeatureConfig(featureKey: string): Promise<FeatureModelConfigRecord | null> {
    return this.db.prepare(`${FEATURE_SELECT} WHERE feature_key = ?`)
      .bind(featureKey)
      .first<FeatureModelConfigRecord>();
  }

  async hasEnabledProvider(providerId: number): Promise<boolean> {
    const row = await this.db.prepare("SELECT id FROM model_provider WHERE id = ? AND enabled = 1")
      .bind(providerId)
      .first<{ id: number }>();
    return row !== null;
  }

  async saveFeatureConfig(input: FeatureModelConfigInput): Promise<void> {
    await this.db.prepare(`
      INSERT INTO feature_model_config (
        feature_key, enabled, primary_provider_id, primary_model, primary_api_style,
        fallback_provider_id, fallback_model, fallback_api_style
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(feature_key) DO UPDATE SET
        enabled = excluded.enabled,
        primary_provider_id = excluded.primary_provider_id,
        primary_model = excluded.primary_model,
        primary_api_style = excluded.primary_api_style,
        fallback_provider_id = excluded.fallback_provider_id,
        fallback_model = excluded.fallback_model,
        fallback_api_style = excluded.fallback_api_style,
        updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
    `).bind(input.featureKey, input.enabled ? 1 : 0,
      input.enabled ? input.primaryProviderId : null,
      input.enabled ? input.primaryModel : null,
      input.enabled ? input.primaryApiStyle : null,
      input.enabled ? input.fallbackProviderId : null,
      input.enabled ? input.fallbackModel : null,
      input.enabled ? input.fallbackApiStyle : null).run();
  }
}
