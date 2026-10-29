import type { EmailReminderBillingPort } from "../interfaces/email-reminder-repository.js";
import { fetchWithServiceBinding, type WorkerServiceBinding } from "@dinqor/worker-runtime";
import { coreServiceRequest } from "./core-service-request.js";

export interface CoreEmailReminderBillingEnvironment {
  CORE_SERVICE_URL?: string;
  CORE_SERVICE_TOKEN?: string;
  CORE_SERVICE?: WorkerServiceBinding;
}

export class CoreEmailReminderBillingMapper implements EmailReminderBillingPort {
  constructor(private readonly env: CoreEmailReminderBillingEnvironment) {}

  async flatPoints(bizType: string): Promise<number> {
    const response = await this.post("/internal/agent/billing/flat-points", { bizType });
    const points = response && typeof response === "object" ? (response as Record<string, unknown>).points : null;
    if (typeof points !== "number" || !Number.isSafeInteger(points) || points < 0) {
      throw new Error("Core returned invalid email reminder billing points");
    }
    return points;
  }

  async charge(input: { userId: number; bizType: string; bizKey: string; bizRef: string; points: number; remark: string }): Promise<void> {
    await this.post("/internal/agent/billing/charge-flat", input);
  }

  private async post(path: string, body: Record<string, unknown>): Promise<unknown> {
    const core = coreServiceRequest(this.env, path);
    const response = await fetchWithServiceBinding(this.env.CORE_SERVICE, core.url, {
      method: "POST",
      headers: { "content-type": "application/json", "x-dinqor-service-token": core.token },
      body: JSON.stringify(body),
      redirect: "manual",
      signal: AbortSignal.timeout(10_000),
    });
    if (!response.ok) throw new Error("Core billing request failed with HTTP " + response.status);
    return response.json();
  }
}
