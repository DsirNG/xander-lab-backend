export type BlogAgentModelStage = "analyze" | "research" | "article" | "review" | "revise" | "compliance";

export interface BlogAgentModelInput {
  instruction: string;
  input: string;
  webSearch?: boolean;
}

export interface BlogAgentModelUsage {
  inputTokens: number;
  cachedTokens: number;
  outputTokens: number;
}

export interface BlogAgentModelResponse extends BlogAgentModelUsage {
  payloadJson: string;
  model: string;
}

export interface BlogAgentModelCandidate {
  providerId: number;
  baseUrl: string;
  apiKey: string;
  model: string;
  apiStyle: string;
}

export interface BlogAgentModelCandidatePort {
  candidates(): Promise<BlogAgentModelCandidate[]>;
}

export interface BlogAgentModelPort {
  complete(input: BlogAgentModelInput): Promise<BlogAgentModelResponse>;
}
