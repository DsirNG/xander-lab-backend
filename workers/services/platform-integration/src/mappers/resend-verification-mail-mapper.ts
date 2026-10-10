import type { PlatformEnvironment } from "../interfaces/platform-environment.js";
import type { VerificationMailPort } from "../interfaces/verification-mail-port.js";

export class ResendVerificationMailMapper implements VerificationMailPort {
  constructor(private readonly env: PlatformEnvironment) {}

  isConfigured(): boolean {
    return Boolean(this.env.RESEND_API_KEY && this.env.MAIL_FROM);
  }

  async send(email: string, code: string): Promise<boolean> {
    if (!this.isConfigured()) return false;
    const response = await fetch("https://api.resend.com/emails", {
      method: "POST",
      headers: {
        authorization: `Bearer ${this.env.RESEND_API_KEY}`,
        "content-type": "application/json",
      },
      body: JSON.stringify({
        from: this.env.MAIL_FROM,
        to: [email],
        subject: "【DinQorAI】登录验证码",
        text: `DinQorAI 登录验证码：${code}\n\n验证码 5 分钟内有效。请勿告诉他人。若非本人操作，请忽略本邮件。`,
        html: `<!doctype html><html lang="zh-CN"><meta charset="utf-8"><body style="font-family:Arial,sans-serif;color:#1f2937"><main style="max-width:520px;margin:32px auto;padding:28px;border:1px solid #e5e7eb"><h1 style="font-size:22px">DinQorAI 登录验证码</h1><p>请在页面输入验证码，5 分钟内有效：</p><p style="font:700 32px monospace;letter-spacing:.3em">${code}</p><p style="color:#6b7280">请勿将验证码告诉他人。若非本人操作，请忽略本邮件。</p></main></body></html>`,
      }),
      redirect: "manual",
      signal: AbortSignal.timeout(10_000),
    });
    return response.ok;
  }
}
