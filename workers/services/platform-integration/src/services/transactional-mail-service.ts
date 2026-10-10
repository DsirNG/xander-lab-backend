import type { TransactionalMailPort } from "../interfaces/transactional-mail-port.js";

const EMAIL_PATTERN = /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/;

export class TransactionalMailError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
    this.name = "TransactionalMailError";
  }
}

export class TransactionalMailService {
  constructor(private readonly mail: TransactionalMailPort) {}

  async send(input: unknown): Promise<void> {
    if (!input || typeof input !== "object" || Array.isArray(input)) {
      throw new TransactionalMailError(400, "邮件内容格式不正确");
    }
    const value = input as Record<string, unknown>;
    const email = typeof value.email === "string" ? value.email.trim().toLowerCase() : "";
    const subject = typeof value.subject === "string" ? value.subject.trim() : "";
    const text = typeof value.text === "string" ? value.text : "";
    const html = value.html === undefined || value.html === null ? null
      : typeof value.html === "string" ? value.html : false;
    if (!EMAIL_PATTERN.test(email) || email.length > 254 || !subject || subject.length > 200
      || /[\r\n]/.test(subject) || text.length > 20_000 || html === false
      || typeof html === "string" && html.length > 40_000) {
      throw new TransactionalMailError(400, "邮件内容格式不正确");
    }
    if (!this.mail.isConfigured()) throw new TransactionalMailError(503, "邮件服务尚未配置");
    try {
      if (!await this.mail.send({ email, subject, text, html })) throw new Error("provider rejected delivery");
    } catch {
      throw new TransactionalMailError(503, "邮件投递暂时失败");
    }
  }
}
