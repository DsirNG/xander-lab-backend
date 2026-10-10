import type { AgentBillingPort, AgentTurnCharge } from "../interfaces/agent-billing-port.js";
import type { AgentModelEnvironment } from "../interfaces/agent-model-gateway.js";

interface CoreResult {
  code?: unknown;
  message?: unknown;
}

/** Calls Core's service-token-protected billing contract over HTTPS across accounts. */
export class CoreAgentBillingMapper implements AgentBillingPort {
  constructor(private readonly env: AgentModelEnvironment) {}

  async ensureAvailable(userId: number): Promise<boolean> {
    const response = await this.call("/internal/agent/billing/ensure-available", { userId });
    if (response.status === 402) return false;
    await this.requireSuccess(response);
    return true;
  }

  async chargeTokens(charge: AgentTurnCharge): Promise<void> {
    const response = await this.call("/internal/agent/billing/charge-tokens", charge);
    await this.requireSuccess(response);
  }

  private async call(path: string, body: unknown): Promise<Response> {
    const baseUrl = this.env.CORE_SERVICE_URL?.trim();
    const serviceToken = this.env.CORE_SERVICE_TOKEN;
    if (!baseUrl || !serviceToken) throw new Error("Core billing contract is not configured");

    let url: URL;
    try {
      url = new URL(path, baseUrl);
    } catch {
      throw new Error("Core billing contract is not configured");
    }
    if (url.protocol !== "https:" && url.hostname !== "127.0.0.1" && url.hostname !== "localhost") {
      throw new Error("Core billing contract must use HTTPS");
    }

    const request = new Request(url, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-dinqor-service-token": serviceToken,
      },
      body: JSON.stringify(body),
      redirect: "manual",
      signal: AbortSignal.timeout(5_000),
    });
    return this.env.CORE_SERVICE ? this.env.CORE_SERVICE.fetch(request) : fetch(request);
  }

  private async requireSuccess(response: Response): Promise<void> {
    if (!response.ok) throw new Error("Core billing contract failed");
    let result: CoreResult;
    try {
      const value: unknown = await response.json();
      if (!value || typeof value !== "object" || Array.isArray(value)) {
        throw new Error("Invalid Core result");
      }
      result = value as CoreResult;
    } catch {
      throw new Error("Core billing contract failed");
    }
    if (result.code !== 200) throw new Error("Core billing contract failed");
  }
}
