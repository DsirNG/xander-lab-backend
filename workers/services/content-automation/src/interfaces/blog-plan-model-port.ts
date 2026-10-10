export type BlogPlanModelStage = "analyze" | "research" | "article" | "review" | "revise" | "compliance";

export interface BlogPlanModelResponse {
  payloadJson: string;
  inputTokens: number;
  cachedTokens: number;
  outputTokens: number;
  model: string;
}

export interface BlogPlanModelPort {
  runStage(stage: BlogPlanModelStage, input: Record<string, unknown>): Promise<BlogPlanModelResponse>;
  ensureAvailable(userId: number): Promise<void>;
  chargeTokens(input: {
    userId: number;
    runId: number;
    planId: number;
    usage: { inputTokens: number; cachedTokens: number; outputTokens: number };
    model: string;
  }): Promise<void>;
}
