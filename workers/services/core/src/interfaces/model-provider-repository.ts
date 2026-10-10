export interface ModelProviderRecord {
  id: number;
  name: string;
  vendor: string;
  baseUrl: string;
  encryptedApiKey: string;
  description: string | null;
  enabled: number;
  priority: number;
  createdAt: string;
  updatedAt: string;
}

export interface ModelProviderInput {
  name: string;
  vendor: string;
  baseUrl: string;
  encryptedApiKey: string;
  description: string | null;
  enabled: boolean;
  priority: number;
}

export interface ModelProviderRepository {
  listProviders(): Promise<ModelProviderRecord[]>;
  findProvider(id: number): Promise<ModelProviderRecord | null>;
  hasProviderName(name: string, exceptId?: number): Promise<boolean>;
  createProvider(input: ModelProviderInput): Promise<ModelProviderRecord | null>;
  updateProvider(id: number, input: ModelProviderInput): Promise<ModelProviderRecord | null>;
  setProviderEnabled(id: number, enabled: boolean): Promise<void>;
  countFeatureReferences(id: number): Promise<number>;
  deleteProvider(id: number): Promise<void>;
}
