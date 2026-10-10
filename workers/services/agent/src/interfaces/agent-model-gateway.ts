export interface AgentModelEndpoint {
  providerId: number;
  baseUrl: string;
  apiKey: string;
  model: string;
  apiStyle: "RESPONSES" | "CHAT_COMPLETIONS";
}

export interface AgentModelGateway {
  candidates(): Promise<{ managed: boolean; endpoints: AgentModelEndpoint[] }>;
}

export interface AgentModelEnvironment {
  CORE_SERVICE_URL?: string;
  CORE_SERVICE_TOKEN?: string;
  CORE_SERVICE?: { fetch(request: Request): Promise<Response> };
  AGENT_MODEL_BASE_URL?: string;
  AGENT_MODEL_API_KEY?: string;
  AGENT_MODEL_NAME?: string;
  AGENT_MODEL_API_STYLE?: string;
}
