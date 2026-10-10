import type { AgentModelEndpoint, AgentModelEnvironment, AgentModelGateway } from "../interfaces/agent-model-gateway.js";

interface GatewayResponse {
  managed?: unknown;
  candidates?: unknown;
}

function isEndpoint(value: unknown): value is AgentModelEndpoint {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  const item = value as Record<string, unknown>;
  if (typeof item.providerId !== "number" || !Number.isSafeInteger(item.providerId)
    || typeof item.baseUrl !== "string" || typeof item.apiKey !== "string"
    || typeof item.model !== "string") return false;
  let url: URL;
  try {
    url = new URL(item.baseUrl);
  } catch {
    return false;
  }
  if (url.protocol !== "https:" && url.hostname !== "127.0.0.1" && url.hostname !== "localhost") return false;
  return true;
}

export class CoreModelGatewayMapper implements AgentModelGateway {
  constructor(private readonly env: AgentModelEnvironment) {}

  async candidates(): Promise<{ managed: boolean; endpoints: AgentModelEndpoint[] }> {
    if (this.env.CORE_SERVICE_URL && this.env.CORE_SERVICE_TOKEN) {
      const url = new URL("/internal/agent/model-config", this.env.CORE_SERVICE_URL);
      if (url.protocol !== "https:" && url.hostname !== "127.0.0.1" && url.hostname !== "localhost") {
        throw new Error("模型服务配置暂不可用");
      }
      const request = new Request(url, {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "x-dinqor-service-token": this.env.CORE_SERVICE_TOKEN,
        },
        body: "{}",
        redirect: "manual",
        signal: AbortSignal.timeout(5_000),
      });
      const response = this.env.CORE_SERVICE
        ? await this.env.CORE_SERVICE.fetch(request)
        : await fetch(request);
      if (!response.ok) throw new Error("模型服务配置暂不可用");
      const value: unknown = await response.json();
      if (!value || typeof value !== "object" || Array.isArray(value)) {
        throw new Error("模型服务配置暂不可用");
      }
      const result = value as GatewayResponse;
      if (typeof result.managed !== "boolean" || !Array.isArray(result.candidates)) {
        throw new Error("模型服务配置暂不可用");
      }
      const candidates = result.candidates.filter(isEndpoint).map((endpoint) => ({
        ...endpoint,
        apiStyle: endpoint.apiStyle === "CHAT_COMPLETIONS" ? "CHAT_COMPLETIONS" as const : "RESPONSES" as const,
      }));
      if (result.managed) return { managed: true, endpoints: candidates };
    }

    const baseUrl = this.env.AGENT_MODEL_BASE_URL?.trim().replace(/\/+$/, "");
    const apiKey = this.env.AGENT_MODEL_API_KEY?.trim();
    const model = this.env.AGENT_MODEL_NAME?.trim();
    if (!baseUrl || !apiKey || !model) return { managed: false, endpoints: [] };
    let parsed: URL;
    try {
      parsed = new URL(baseUrl);
    } catch {
      return { managed: false, endpoints: [] };
    }
    if (parsed.protocol !== "https:" && parsed.hostname !== "127.0.0.1" && parsed.hostname !== "localhost") {
      return { managed: false, endpoints: [] };
    }
    const apiStyle = this.env.AGENT_MODEL_API_STYLE?.trim().toUpperCase() === "CHAT_COMPLETIONS"
      ? "CHAT_COMPLETIONS" : "RESPONSES";
    return {
      managed: false,
      endpoints: [{ providerId: 0, baseUrl, apiKey, model, apiStyle }],
    };
  }
}
