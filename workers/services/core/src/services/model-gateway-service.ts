import type { D1Binding } from "@dinqor/worker-runtime";
import { decryptSecret } from "./secret-service.js";
import { D1ModelGatewayMapper } from "../mappers/d1-model-gateway-mapper.js";
import type {
  AgentModelEndpoint,
  AgentModelGatewaySnapshot,
} from "../interfaces/model-gateway-repository.js";

export interface ModelGatewayEnvironment {
  DB: D1Binding;
  ADMIN_MODEL_GATEWAY_ENABLED?: string;
  MODEL_PROVIDER_ENCRYPTION_KEY?: string;
}

export async function resolveAgentModelGateway(
  env: ModelGatewayEnvironment,
): Promise<AgentModelGatewaySnapshot> {
  return resolveFeatureModelGateway(env, "agent");
}

export async function resolveFeatureModelGateway(
  env: ModelGatewayEnvironment,
  featureKey: string,
): Promise<AgentModelGatewaySnapshot> {
  if (env.ADMIN_MODEL_GATEWAY_ENABLED !== "true") return { managed: false, candidates: [] };
  const mapper = new D1ModelGatewayMapper(env.DB);
  const config = await mapper.findFeatureConfig(featureKey);
  if (!config || config.enabled !== 1) return { managed: true, candidates: [] };

  const slots = [
    { id: config.primaryProviderId, model: config.primaryModel, apiStyle: config.primaryApiStyle },
    { id: config.fallbackProviderId, model: config.fallbackModel, apiStyle: config.fallbackApiStyle },
  ];
  const candidates: AgentModelEndpoint[] = [];
  for (const slot of slots) {
    if (!slot.id || !slot.model?.trim()) continue;
    const provider = await mapper.findEnabledProvider(slot.id);
    if (!provider || !provider.baseUrl || !provider.encryptedApiKey) continue;
    let providerUrl: URL;
    try {
      providerUrl = new URL(provider.baseUrl);
    } catch {
      continue;
    }
    if (providerUrl.protocol !== "https:" && providerUrl.hostname !== "127.0.0.1"
      && providerUrl.hostname !== "localhost") continue;
    const apiKey = await decryptSecret(provider.encryptedApiKey, env.MODEL_PROVIDER_ENCRYPTION_KEY);
    if (!apiKey) continue;
    candidates.push({
      providerId: provider.id,
      baseUrl: provider.baseUrl.replace(/\/+$/, ""),
      apiKey,
      model: slot.model.trim(),
      apiStyle: slot.apiStyle?.trim() || "RESPONSES",
    });
  }
  return { managed: true, candidates };
}
