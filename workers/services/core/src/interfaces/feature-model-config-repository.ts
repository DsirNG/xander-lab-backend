export interface FeatureModelConfigRecord {
  featureKey: string;
  enabled: number;
  primaryProviderId: number | null;
  primaryModel: string | null;
  primaryApiStyle: string | null;
  fallbackProviderId: number | null;
  fallbackModel: string | null;
  fallbackApiStyle: string | null;
  updatedAt: string | null;
}

export interface FeatureModelConfigInput {
  featureKey: string;
  enabled: boolean;
  primaryProviderId: number | null;
  primaryModel: string | null;
  primaryApiStyle: string | null;
  fallbackProviderId: number | null;
  fallbackModel: string | null;
  fallbackApiStyle: string | null;
}

export interface FeatureModelConfigRepository {
  listFeatureConfigs(): Promise<FeatureModelConfigRecord[]>;
  findFeatureConfig(featureKey: string): Promise<FeatureModelConfigRecord | null>;
  hasEnabledProvider(providerId: number): Promise<boolean>;
  saveFeatureConfig(input: FeatureModelConfigInput): Promise<void>;
}
