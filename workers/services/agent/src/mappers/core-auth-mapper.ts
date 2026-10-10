import { apiError } from "@dinqor/worker-runtime";
import type { AgentAuthRepository } from "../interfaces/agent-auth.js";
import type { AgentEnvironment } from "../interfaces/agent-environment.js";

export class CoreAuthMapper implements AgentAuthRepository {
  constructor(private readonly env: AgentEnvironment) {}

  async resolveActiveUser(accessToken: string): Promise<number | null | Response> {
    if (!this.env.CORE_SERVICE_URL || !this.env.CORE_SERVICE_TOKEN) {
      return apiError(503, "身份服务暂不可用");
    }
    try {
      const endpoint = new URL("/internal/agent/auth/resolve", this.env.CORE_SERVICE_URL);
      if (endpoint.protocol !== "https:" && endpoint.hostname !== "127.0.0.1"
        && endpoint.hostname !== "localhost") return apiError(503, "身份服务暂不可用");
      const request = new Request(endpoint, {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "x-dinqor-service-token": this.env.CORE_SERVICE_TOKEN,
        },
        body: JSON.stringify({ accessToken }),
        redirect: "manual",
        signal: AbortSignal.timeout(5_000),
      });
      const response = this.env.CORE_SERVICE
        ? await this.env.CORE_SERVICE.fetch(request)
        : await fetch(request);
      if (!response.ok) {
        console.error("Agent Core auth resolver returned HTTP", response.status);
        return apiError(503, "身份服务暂不可用");
      }
      const result: unknown = await response.json();
      if (!result || typeof result !== "object" || Array.isArray(result)) {
        return apiError(503, "身份服务暂不可用");
      }
      const userId = (result as Record<string, unknown>).userId;
      return typeof userId === "number" && Number.isSafeInteger(userId) && userId > 0
        ? userId : null;
    } catch (error) {
      console.error(
        "Agent Core auth resolver request failed",
        error instanceof Error ? error.message : "unknown error",
      );
      return apiError(503, "身份服务暂不可用");
    }
  }
}
