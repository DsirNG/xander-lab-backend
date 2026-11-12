import type { ModelProbeInput, ModelProbePort, ModelProbeResult } from "../interfaces/model-probe.js";
import { ModelProviderService } from "./model-provider-service.js";
import { decryptSecret } from "./secret-service.js";

export class ModelProbeService {
  constructor(private readonly providers: ModelProviderService, private readonly remote: ModelProbePort) {}

  async test(input: Omit<ModelProbeInput, "baseUrl" | "apiKey"> & { providerId?: number; baseUrl?: string; apiKey?: string }, encryptionKey?: string): Promise<ModelProbeResult> {
    const provider = input.providerId ? await this.providers.find(input.providerId) : null;
    if (input.providerId && !provider) return { ok: false, reason: "provider_missing", latencyMs: 0 };
    const baseUrl = input.baseUrl || provider?.baseUrl || "";
    const apiKey = input.apiKey || (provider ? await decryptSecret(provider.encryptedApiKey, encryptionKey) : null);
    if (!apiKey) return { ok: false, reason: "key_missing", latencyMs: 0 };
    if (input.featureKey && provider?.enabled !== 1) return { ok: false, reason: "provider_disabled", latencyMs: 0 };
    return this.remote.probe({ ...input, baseUrl, apiKey });
  }
}
