import type { PlatformEnvironment } from "../interfaces/platform-environment.js";
import type { TransactionalMailPort } from "../interfaces/transactional-mail-port.js";

export class ResendTransactionalMailMapper implements TransactionalMailPort {
  constructor(private readonly env: PlatformEnvironment) {}

  isConfigured(): boolean {
    return Boolean(this.env.RESEND_API_KEY && this.env.MAIL_FROM);
  }

  async send(input: { email: string; subject: string; text: string; html: string | null }): Promise<boolean> {
    if (!this.isConfigured()) return false;
    const response = await fetch("https://api.resend.com/emails", {
      method: "POST",
      headers: { authorization: "Bearer " + this.env.RESEND_API_KEY, "content-type": "application/json" },
      body: JSON.stringify({
        from: this.env.MAIL_FROM,
        to: [input.email],
        subject: input.subject,
        text: input.text,
        ...(input.html === null ? {} : { html: input.html }),
      }),
      redirect: "manual",
      signal: AbortSignal.timeout(10_000),
    });
    return response.ok;
  }
}
