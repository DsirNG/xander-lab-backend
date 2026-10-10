import type { EmailReminderBillingPort } from "../interfaces/email-reminder-repository.js";

export interface CoreEmailReminderBillingEnvironment {
  CORE_SERVICE_URL?: string;
  CORE_SERVICE_TOKEN?: string;
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
    if (!this.env.CORE_SERVICE_URL || !this.env.CORE_SERVICE_TOKEN) throw new Error("Core service is not configured");
    const response = await fetch(new URL(path, this.env.CORE_SERVICE_URL), {
      method: "POST",
      headers: { "content-type": "application/json", "x-dinqor-service-token": this.env.CORE_SERVICE_TOKEN },
      body: JSON.stringify(body),
      redirect: "manual",
      signal: AbortSignal.timeout(10_000),
    });
    if (!response.ok) throw new Error("Core billing request failed with HTTP " + response.status);
    return response.json();
  }
}
