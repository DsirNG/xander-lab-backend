import type { EmailReminderMailPort } from "../interfaces/email-reminder-repository.js";
import { fetchWithServiceBinding, type WorkerServiceBinding } from "@dinqor/worker-runtime";

export interface PlatformReminderMailEnvironment {
  PLATFORM_INTEGRATION_SERVICE_URL?: string;
  PLATFORM_INTEGRATION_SERVICE_TOKEN?: string;
  PLATFORM_INTEGRATION_SERVICE?: WorkerServiceBinding;
}

export class PlatformReminderMailMapper implements EmailReminderMailPort {
  constructor(private readonly env: PlatformReminderMailEnvironment) {}

  async send(email: string, subject: string, text: string, html: string | null): Promise<boolean> {
    if (!this.env.PLATFORM_INTEGRATION_SERVICE_URL || !this.env.PLATFORM_INTEGRATION_SERVICE_TOKEN) return false;
    const response = await fetchWithServiceBinding(this.env.PLATFORM_INTEGRATION_SERVICE,
      new URL("/internal/platform-integration/mail/reminder", this.env.PLATFORM_INTEGRATION_SERVICE_URL), {
      method: "POST",
      headers: { "content-type": "application/json", "x-dinqor-service-token": this.env.PLATFORM_INTEGRATION_SERVICE_TOKEN },
      body: JSON.stringify({ email, subject, text, html }),
      redirect: "manual",
      signal: AbortSignal.timeout(10_000),
      });
    return response.ok;
  }
}
