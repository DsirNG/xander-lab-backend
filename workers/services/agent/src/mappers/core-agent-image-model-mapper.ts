import type { AgentImageModelEnvironment } from "../interfaces/agent-image-model-environment.js";
import type { AgentImageModelEndpoint, AgentImageModelGateway } from "../interfaces/agent-image-model-gateway.js";

function endpoint(value: unknown): value is AgentImageModelEndpoint {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  const item = value as Record<string, unknown>;
  if (typeof item.providerId !== "number" || !Number.isSafeInteger(item.providerId)
    || typeof item.baseUrl !== "string" || typeof item.apiKey !== "string" || typeof item.model !== "string"
    || !["IMAGES_GENERATIONS", "CHAT_COMPLETIONS", "CHAT_PROMPT"].includes(String(item.apiStyle))) return false;
  try {
    const url = new URL(item.baseUrl);
    return url.protocol === "https:" || url.hostname === "127.0.0.1" || url.hostname === "localhost";
  } catch { return false; }
}

function localEndpoint(env: AgentImageModelEnvironment): AgentImageModelEndpoint | null {
  const baseUrl = env.AGENT_IMAGE_MODEL_BASE_URL?.trim().replace(/\/+$/, "");
  const apiKey = env.AGENT_IMAGE_MODEL_API_KEY?.trim();
  const model = env.AGENT_IMAGE_MODEL_NAME?.trim();
  if (!baseUrl || !apiKey || !model) return null;
  let url: URL;
  try { url = new URL(baseUrl); } catch { return null; }
  if (url.protocol !== "https:" && url.hostname !== "127.0.0.1" && url.hostname !== "localhost") return null;
  const configuredStyle = env.AGENT_IMAGE_MODEL_API_STYLE?.trim().toUpperCase();
  const apiStyle = configuredStyle === "CHAT_COMPLETIONS" || configuredStyle === "CHAT_PROMPT"
    ? configuredStyle : "IMAGES_GENERATIONS";
  return { providerId: 0, baseUrl, apiKey, model, apiStyle };
}

/** Resolves Core's encrypted, feature-scoped image model candidates across accounts. */
export class CoreAgentImageModelMapper implements AgentImageModelGateway {
  constructor(private readonly env: AgentImageModelEnvironment) {}

  async candidates(): Promise<{ managed: boolean; endpoints: AgentImageModelEndpoint[] }> {
    if (this.env.CORE_SERVICE_URL && this.env.CORE_SERVICE_TOKEN) {
      let url: URL;
      try { url = new URL("/internal/agent/image-model-config", this.env.CORE_SERVICE_URL); }
      catch { throw new Error("Image model configuration is unavailable"); }
      if (url.protocol !== "https:" && url.hostname !== "localhost" && url.hostname !== "127.0.0.1") {
        throw new Error("Core model contract must use HTTPS");
      }
      const request = new Request(url, {
        method: "POST",
        headers: { "content-type": "application/json", "x-dinqor-service-token": this.env.CORE_SERVICE_TOKEN },
        body: "{}", redirect: "manual", signal: AbortSignal.timeout(5_000),
      });
      const response = this.env.CORE_SERVICE ? await this.env.CORE_SERVICE.fetch(request) : await fetch(request);
      if (!response.ok) throw new Error("Image model configuration is unavailable");
      const value: unknown = await response.json();
      if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("Invalid image model configuration");
      const result = value as Record<string, unknown>;
      if (typeof result.managed !== "boolean" || !Array.isArray(result.candidates)) {
        throw new Error("Invalid image model configuration");
      }
      const endpoints = result.candidates.filter(endpoint);
      if (result.managed) return { managed: true, endpoints };
    }
    const fallback = localEndpoint(this.env);
    return { managed: false, endpoints: fallback ? [fallback] : [] };
  }
}
