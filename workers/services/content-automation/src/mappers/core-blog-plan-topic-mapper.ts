import type { BlogPlanTopicInput, BlogPlanTopicPort } from "../interfaces/blog-plan-topic-port.js";
import { fetchWithServiceBinding, type WorkerServiceBinding } from "@dinqor/worker-runtime";

export interface CoreBlogPlanTopicEnvironment {
  CORE_SERVICE_URL?: string;
  CORE_SERVICE_TOKEN?: string;
  CORE_SERVICE?: WorkerServiceBinding;
}

export class CoreBlogPlanTopicMapper implements BlogPlanTopicPort {
  constructor(private readonly env: CoreBlogPlanTopicEnvironment) {}

  async generateTopics(userId: number, input: BlogPlanTopicInput): Promise<string[]> {
    if (!this.env.CORE_SERVICE_URL || !this.env.CORE_SERVICE_TOKEN) {
      throw new Error("Core blog model service is not configured");
    }
    const response = await fetchWithServiceBinding(this.env.CORE_SERVICE,
      new URL("/internal/agent/blog/topic-series", this.env.CORE_SERVICE_URL), {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-dinqor-service-token": this.env.CORE_SERVICE_TOKEN,
      },
      body: JSON.stringify({
        userId,
        seed: input.seed,
        count: input.count,
        audience: input.audience,
        tone: input.tone,
        excludeTitles: input.excludeTitles,
      }),
      signal: AbortSignal.timeout(90_000),
      redirect: "manual",
    });
    if (!response.ok) throw new Error(`Core blog model returned HTTP ${response.status}`);
    const payload: unknown = await response.json();
    const topics = payload && typeof payload === "object" && !Array.isArray(payload)
      ? (payload as Record<string, unknown>).topics : null;
    if (!Array.isArray(topics) || topics.some((topic) => typeof topic !== "string")) {
      throw new Error("Core blog model returned invalid topics");
    }
    return topics as string[];
  }
}
