import type { AgentImageModelEndpoint } from "./agent-image-model-gateway.js";

export interface AgentGeneratedImage {
  bytes: Uint8Array;
  contentType: string;
  extension: string;
  model: string;
  size: string;
  usage: { inputTokens: number; outputTokens: number; cachedTokens: number };
}

export interface AgentImageGenerationPort {
  generate(endpoint: AgentImageModelEndpoint, prompt: string): Promise<AgentGeneratedImage>;
}
