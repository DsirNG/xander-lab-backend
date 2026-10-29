import type { BlogPlanModelPort, BlogPlanModelResponse, BlogPlanModelStage } from "../interfaces/blog-plan-model-port.js";
import { fetchWithServiceBinding, type WorkerServiceBinding } from "@dinqor/worker-runtime";
import { coreServiceRequest } from "./core-service-request.js";

export interface CoreBlogPlanModelEnvironment {
  CORE_SERVICE_URL?: string;
  CORE_SERVICE_TOKEN?: string;
  CORE_SERVICE?: WorkerServiceBinding;
}

export class CoreBlogPlanModelMapper implements BlogPlanModelPort {
  constructor(private readonly env: CoreBlogPlanModelEnvironment) {}

  async runStage(stage: BlogPlanModelStage, input: Record<string, unknown>): Promise<BlogPlanModelResponse> {
    const payload = await this.post(`/internal/agent/blog/model/${stage}`, input, 185_000);
    if (!payload || typeof payload !== "object" || Array.isArray(payload)) {
      throw new Error("Core blog model returned an invalid response");
    }
    const result = payload as Record<string, unknown>;
    if (typeof result.payloadJson !== "string" || typeof result.model !== "string") {
      throw new Error("Core blog model returned an invalid response");
    }
    const count = (value: unknown) => typeof value === "number" && Number.isSafeInteger(value) && value >= 0 ? value : 0;
    return {
      payloadJson: result.payloadJson,
      inputTokens: count(result.inputTokens),
      cachedTokens: count(result.cachedTokens),
      outputTokens: count(result.outputTokens),
      model: result.model,
    };
  }

  async ensureAvailable(userId: number): Promise<void> {
    await this.post("/internal/agent/billing/ensure-available", { userId });
  }

  async chargeTokens(input: {
    userId: number; runId: number; planId: number;
    usage: { inputTokens: number; cachedTokens: number; outputTokens: number }; model: string;
  }): Promise<void> {
    await this.post("/internal/agent/billing/charge-tokens", {
      userId: input.userId,
      bizType: "BLOG_AGENT_RUN",
      bizKey: `plan-run:${input.runId}`,
      bizRef: `plan:${input.planId}`,
      inputTokens: input.usage.inputTokens,
      cachedTokens: input.usage.cachedTokens,
      outputTokens: input.usage.outputTokens,
      model: input.model,
      remark: "定时博客生成",
    });
  }

  private async post(path: string, body: Record<string, unknown>, timeoutMs = 90_000): Promise<unknown> {
    const core = coreServiceRequest(this.env, path);
    const response = await fetchWithServiceBinding(this.env.CORE_SERVICE, core.url, {
      method: "POST",
      headers: { "content-type": "application/json", "x-dinqor-service-token": core.token },
      body: JSON.stringify(body),
      redirect: "manual",
      signal: AbortSignal.timeout(timeoutMs),
    });
    if (!response.ok) {
      let message = `Core service returned HTTP ${response.status}`;
      try {
        const payload: unknown = await response.json();
        if (payload && typeof payload === "object" && typeof (payload as Record<string, unknown>).message === "string") {
          message = (payload as Record<string, unknown>).message as string;
        }
      } catch { /* Keep the safe status-based error. */ }
      const error = new Error(message) as Error & { status?: number };
      error.status = response.status;
      throw error;
    }
    return response.json();
  }
}
