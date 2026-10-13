import type { PlatformEnvironment } from "../interfaces/platform-environment.js";
import type { VerificationMailPort } from "../interfaces/verification-mail-port.js";

const CODE_EXPIRE_MINUTES = 5;
const FONT = "-apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC','Hiragino Sans GB','Microsoft YaHei',Arial,sans-serif";

function escapeHtml(value: string): string {
  return value.replace(/[&<>"']/g, (character) => {
    switch (character) {
      case "&": return "&amp;";
      case "<": return "&lt;";
      case ">": return "&gt;";
      case "\"": return "&quot;";
      case "'": return "&#39;";
      default: return character;
    }
  });
}

function buildCodeCells(code: string): string {
  return [...code].map((digit) =>
    `<td align="center" valign="middle" style="width:34px;height:52px;border:1px solid #dbe3ef;border-radius:8px;background:#f8faff;color:#172033;font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:25px;font-weight:700;">${escapeHtml(digit)}</td>`,
  ).join('<td aria-hidden="true" style="width:3px;font-size:0;line-height:0;">&nbsp;</td>');
}

function buildPlainText(code: string): string {
  return `DinQorAI 登录验证码\n\n您正在登录 DinQorAI，请在登录页面输入以下验证码：\n\n${code}\n\n验证码 ${CODE_EXPIRE_MINUTES} 分钟内有效。\n\n安全提醒：\n· 验证码仅用于本次登录，请勿告诉他人。\n· 请只在 DinQorAI 登录页面输入验证码，任何人都不应向您索取这组数字。\n· 如果这不是您发起的操作，请忽略本邮件。\n\n本邮件由 DinQorAI 自动发送，请勿直接回复。`;
}

function buildHtml(code: string): string {
  return `<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>DinQorAI 登录验证码</title>
</head>
<body style="margin:0;padding:32px 12px;background:#f4f7fb;color:#172033;font-family:${FONT};">
  <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="border-collapse:collapse;">
    <tr><td align="center">
      <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="max-width:520px;border:1px solid #e6ebf2;border-radius:16px;background:#ffffff;border-collapse:separate;">
        <tr><td style="padding:28px 32px 0;">
          <div style="font-size:20px;line-height:28px;font-weight:700;letter-spacing:-.4px;color:#111827;">Din<span style="color:#3978f6;">Qor</span>AI</div>
        </td></tr>
        <tr><td style="padding:26px 32px 0;">
          <h1 style="margin:0;font-size:24px;line-height:32px;font-weight:700;color:#111827;">登录验证码</h1>
          <p style="margin:10px 0 0;font-size:14px;line-height:23px;color:#586579;">您正在登录 DinQorAI。请在登录页面输入以下验证码，完成身份验证。</p>
        </td></tr>
        <tr><td align="center" style="padding:28px 12px 8px;">
          <table role="presentation" cellpadding="0" cellspacing="0" border="0" style="border-collapse:separate;">
            <tr>${buildCodeCells(code)}</tr>
          </table>
        </td></tr>
        <tr><td align="center" style="padding:8px 24px 26px;font-size:13px;line-height:20px;color:#6b778b;">
          验证码 <strong style="color:#27364b;font-weight:600;">${CODE_EXPIRE_MINUTES} 分钟内有效</strong>，请尽快完成登录。
        </td></tr>
        <tr><td style="padding:0 32px 28px;">
          <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="border-collapse:collapse;background:#f7f9fc;border-radius:10px;">
            <tr><td style="padding:15px 16px;font-size:12px;line-height:20px;color:#69768a;">
              <strong style="color:#39465a;font-weight:600;">安全提醒</strong><br>
              请勿将验证码告诉任何人，只在 DinQorAI 登录页面输入。若非本人操作，请忽略本邮件。
            </td></tr>
          </table>
        </td></tr>
        <tr><td style="padding:16px 24px;border-top:1px solid #edf0f5;text-align:center;font-size:11px;line-height:18px;color:#9aa4b2;">
          本邮件由 DinQorAI 自动发送，请勿直接回复。
        </td></tr>
      </table>
    </td></tr>
  </table>
</body>
</html>`;
}

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
        text: buildPlainText(code),
        html: buildHtml(code),
      }),
      redirect: "manual",
      signal: AbortSignal.timeout(10_000),
    });
    return response.ok;
  }
}
