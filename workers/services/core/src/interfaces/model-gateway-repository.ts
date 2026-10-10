export interface AgentModelConfig {
  enabled: number;
  primaryProviderId: number | null;
  primaryModel: string | null;
  primaryApiStyle: string | null;
  fallbackProviderId: number | null;
  fallbackModel: string | null;
  fallbackApiStyle: string | null;
}

export interface ModelProviderConfig {
  id: number;
  baseUrl: string;
  encryptedApiKey: string;
}

export interface ModelGatewayRepository {
  findAgentConfig(): Promise<AgentModelConfig | null>;
  findFeatureConfig(featureKey: string): Promise<AgentModelConfig | null>;
  findEnabledProvider(id: number): Promise<ModelProviderConfig | null>;
}

export interface AgentModelEndpoint {
  providerId: number;
  baseUrl: string;
  apiKey: string;
  model: string;
  apiStyle: string;
}

export interface AgentModelGatewaySnapshot {
  managed: boolean;
  candidates: AgentModelEndpoint[];
}
