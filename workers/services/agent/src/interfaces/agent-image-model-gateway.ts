export interface AgentImageModelEndpoint {
  providerId: number;
  baseUrl: string;
  apiKey: string;
  model: string;
  apiStyle: "IMAGES_GENERATIONS" | "CHAT_COMPLETIONS" | "CHAT_PROMPT";
}

export interface AgentImageModelGateway {
  candidates(): Promise<{ managed: boolean; endpoints: AgentImageModelEndpoint[] }>;
}
