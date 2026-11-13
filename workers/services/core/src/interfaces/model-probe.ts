export interface ModelProbeMessage {
  role: "user" | "assistant";
  content: string;
}
export interface ModelProbeInput {
  baseUrl: string;
  apiKey: string;
  model?: string;
  apiStyle?: string;
  featureKey?: string;
  prompt?: string;
  messages?: ModelProbeMessage[];
  imageDataUrl?: string;
  maxOutputTokens?: number;
  imageSize?: string;
}
export interface ModelProbeError {
  message: string;
  code?: string;
  type?: string;
  param?: string;
  requestId?: string;
}
export interface ModelProbeResult {
  ok: boolean;
  reason: string;
  latencyMs: number;
  upstreamStatus?: number;
  endpoint?: string;
  error?: ModelProbeError;
  output?: { text?: string; images?: string[]; finishReason?: string; truncated?: boolean };
  models?: string[];
  rawResponse?: string;
}
export interface ModelProbePort {
  probe(input: ModelProbeInput): Promise<ModelProbeResult>;
}
