import type { D1Binding } from "@dinqor/worker-runtime";
import type {
  AgentModelConfig,
  ModelGatewayRepository,
  ModelProviderConfig,
} from "../interfaces/model-gateway-repository.js";

export class D1ModelGatewayMapper implements ModelGatewayRepository {
  constructor(private readonly db: D1Binding) {}

  findAgentConfig(): Promise<AgentModelConfig | null> {
    return this.findFeatureConfig("agent");
  }

  findFeatureConfig(featureKey: string): Promise<AgentModelConfig | null> {
    return this.db.prepare(`
      SELECT enabled,
             primary_provider_id AS primaryProviderId, primary_model AS primaryModel,
             primary_api_style AS primaryApiStyle, fallback_provider_id AS fallbackProviderId,
             fallback_model AS fallbackModel, fallback_api_style AS fallbackApiStyle
      FROM feature_model_config WHERE feature_key = ? LIMIT 1
    `).bind(featureKey).first<AgentModelConfig>();
  }

  findEnabledProvider(id: number): Promise<ModelProviderConfig | null> {
    return this.db.prepare(`
      SELECT id, base_url AS baseUrl, encrypted_api_key AS encryptedApiKey
      FROM model_provider WHERE id = ? AND enabled = 1 LIMIT 1
    `).bind(id).first<ModelProviderConfig>();
  }
}
