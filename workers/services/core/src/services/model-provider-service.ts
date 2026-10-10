import type {
  ModelProviderInput,
  ModelProviderRecord,
  ModelProviderRepository,
} from "../interfaces/model-provider-repository.js";
import { encryptSecret } from "./secret-service.js";

export type ModelProviderMutation =
  | { kind: "saved"; provider: ModelProviderRecord }
  | { kind: "missing" }
  | { kind: "duplicate" }
  | { kind: "encryption_unavailable" }
  | { kind: "save_failed" }
  | { kind: "referenced" }
  | { kind: "deleted" };

export class ModelProviderService {
  constructor(private readonly providers: ModelProviderRepository) {}

  list(): Promise<ModelProviderRecord[]> {
    return this.providers.listProviders();
  }

  async create(input: Omit<ModelProviderInput, "encryptedApiKey"> & { apiKey: string }, encryptionKey?: string)
    : Promise<ModelProviderMutation> {
    if (await this.providers.hasProviderName(input.name)) return { kind: "duplicate" };
    const encryptedApiKey = await encryptSecret(input.apiKey, encryptionKey);
    if (!encryptedApiKey) return { kind: "encryption_unavailable" };
    const provider = await this.providers.createProvider({
      name: input.name,
      vendor: input.vendor,
      baseUrl: input.baseUrl,
      encryptedApiKey,
      description: input.description,
      enabled: input.enabled,
      priority: input.priority,
    });
    return provider ? { kind: "saved", provider } : { kind: "save_failed" };
  }

  async setEnabled(id: number, enabled: boolean): Promise<ModelProviderMutation> {
    const existing = await this.providers.findProvider(id);
    if (!existing) return { kind: "missing" };
    await this.providers.setProviderEnabled(id, enabled);
    const provider = await this.providers.findProvider(id);
    return provider ? { kind: "saved", provider } : { kind: "missing" };
  }

  async delete(id: number): Promise<ModelProviderMutation> {
    if (!await this.providers.findProvider(id)) return { kind: "missing" };
    if (await this.providers.countFeatureReferences(id) > 0) return { kind: "referenced" };
    await this.providers.deleteProvider(id);
    return { kind: "deleted" };
  }

  async update(
    id: number,
    input: Omit<ModelProviderInput, "encryptedApiKey"> & { apiKey: string },
    encryptionKey?: string,
  ): Promise<ModelProviderMutation> {
    const existing = await this.providers.findProvider(id);
    if (!existing) return { kind: "missing" };
    if (await this.providers.hasProviderName(input.name, id)) return { kind: "duplicate" };
    const encryptedApiKey = input.apiKey
      ? await encryptSecret(input.apiKey, encryptionKey)
      : existing.encryptedApiKey;
    if (!encryptedApiKey) return { kind: "encryption_unavailable" };
    const provider = await this.providers.updateProvider(id, {
      name: input.name,
      vendor: input.vendor,
      baseUrl: input.baseUrl,
      encryptedApiKey,
      description: input.description,
      enabled: input.enabled,
      priority: input.priority,
    });
    return provider ? { kind: "saved", provider } : { kind: "missing" };
  }
}
