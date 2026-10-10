import type { VerificationMailRepository, VerificationMailResult } from "../interfaces/verification-mail-repository.js";

export interface PlatformIntegrationMailEnvironment {
  PLATFORM_INTEGRATION_SERVICE_URL?: string;
  PLATFORM_INTEGRATION_SERVICE_TOKEN?: string;
}

export class PlatformIntegrationMailMapper implements VerificationMailRepository {
  constructor(private readonly env: PlatformIntegrationMailEnvironment) {}

  async deliver(email: string, code: string): Promise<VerificationMailResult> {
    if (!this.env.PLATFORM_INTEGRATION_SERVICE_URL || !this.env.PLATFORM_INTEGRATION_SERVICE_TOKEN) {
      return "unconfigured";
    }
    try {
      const endpoint = new URL("/internal/mail/verification-code", this.env.PLATFORM_INTEGRATION_SERVICE_URL);
      const response = await fetch(endpoint, {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "x-dinqor-service-token": this.env.PLATFORM_INTEGRATION_SERVICE_TOKEN,
        },
        body: JSON.stringify({ email, code }),
        signal: AbortSignal.timeout(10_000),
      });
      return response.ok ? "sent" : "failed";
    } catch {
      return "failed";
    }
  }
}
