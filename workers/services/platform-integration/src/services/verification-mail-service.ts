import type { VerificationMailPort } from "../interfaces/verification-mail-port.js";

const EMAIL_PATTERN = /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/;

export class VerificationMailError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
    this.name = "VerificationMailError";
  }
}

export class VerificationMailService {
  constructor(private readonly mail: VerificationMailPort) {}

  async send(emailValue: unknown, codeValue: unknown): Promise<void> {
    const email = typeof emailValue === "string" ? emailValue.trim() : "";
    const code = typeof codeValue === "string" ? codeValue.trim() : "";
    if (email.length > 254 || !EMAIL_PATTERN.test(email) || !/^\d{6}$/.test(code)) {
      throw new VerificationMailError(400, "邮箱或验证码格式不正确");
    }
    if (!this.mail.isConfigured()) throw new VerificationMailError(503, "邮件服务尚未配置");
    try {
      if (!await this.mail.send(email, code)) throw new Error("mail provider rejected delivery");
    } catch {
      throw new VerificationMailError(503, "邮件投递暂时失败");
    }
  }
}
