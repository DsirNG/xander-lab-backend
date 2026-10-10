import type { VerificationCodeRepository } from "../interfaces/verification-code-repository.js";
import type { VerificationMailRepository } from "../interfaces/verification-mail-repository.js";

export type SendVerificationCodeResult = "sent" | "unconfigured" | "failed";

export class VerificationCodeService {
  constructor(
    private readonly codes: VerificationCodeRepository,
    private readonly mail: VerificationMailRepository,
  ) {}

  async send(email: string): Promise<SendVerificationCodeResult> {
    const code = String(100_000 + crypto.getRandomValues(new Uint32Array(1))[0] % 900_000);
    await this.codes.save(email, code);
    const delivery = await this.mail.deliver(email, code);
    if (delivery !== "sent") await this.codes.delete(email);
    return delivery;
  }

  verify(email: string, code: string): Promise<boolean> {
    return this.codes.consume(email, code);
  }
}
