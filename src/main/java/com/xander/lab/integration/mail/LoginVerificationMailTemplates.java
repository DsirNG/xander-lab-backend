package com.xander.lab.integration.mail;

import org.springframework.web.util.HtmlUtils;

/**
 * Branded HTML + plain-text bodies for login verification codes.
 */
public final class LoginVerificationMailTemplates {

    public static final int CODE_EXPIRE_MINUTES = 5;

    private static final String FONT =
            "-apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC','Hiragino Sans GB','Microsoft YaHei',sans-serif";

    private LoginVerificationMailTemplates() {
    }

    public static String buildSubject() {
        return "【DinQorAI】登录验证码";
    }

    public static String buildPlainText(String code) {
        String safeCode = code == null ? "" : code.strip();
        return """
                DinQorAI 登录验证码

                您正在登录 DinQorAI，请输入以下验证码：

                %s

                验证码 %d 分钟内有效。请复制或输入这组数字完成登录。

                安全提醒：
                · 验证码仅用于本次登录，请勿告诉他人。
                · DinQorAI 不会通过电话、短信或邮件向您索要验证码。
                · 如非本人操作，请忽略本邮件。

                — DinQorAI 安全中心
                本邮件由系统自动发送，请勿直接回复
                """.formatted(safeCode, CODE_EXPIRE_MINUTES);
    }

    public static String buildHtml(String code) {
        String safeCode = code == null ? "" : code.strip();
        String digits = buildDigitCells(safeCode);
        return """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1.0">
                  <title>DinQorAI 登录验证码</title>
                </head>
                <body style="margin:0;padding:0;background:#f6f8fb;font-family:%s;color:#1f2937;">
                  <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background:#f6f8fb;padding:32px 12px;">
                    <tr>
                      <td align="center">
                        <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="max-width:520px;background:#ffffff;border:1px solid #e5e7eb;">
                          <tr>
                            <td style="padding:28px 32px 20px;border-bottom:1px solid #e5e7eb;">
                              <div style="font-size:16px;font-weight:700;color:#111827;">DinQorAI</div>
                              <div style="margin-top:8px;font-size:24px;line-height:1.35;font-weight:700;color:#111827;">登录验证码</div>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:24px 32px 8px;">
                              <p style="margin:0;font-size:15px;line-height:1.7;color:#374151;">
                                您正在登录 DinQorAI。请在登录页输入下方验证码，验证码将在 <strong>%d 分钟</strong> 后失效。
                              </p>
                            </td>
                          </tr>
                          <tr>
                            <td align="center" style="padding:24px 20px;">
                              <div style="font-size:13px;color:#6b7280;margin-bottom:12px;">您的验证码</div>
                              <div style="display:inline-block;padding:16px 10px;background:#f9fafb;border:1px solid #d1d5db;">
                                %s
                              </div>
                              <div style="margin-top:14px;font-size:13px;line-height:1.6;color:#6b7280;">
                                长按或选中上方数字即可复制验证码
                              </div>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:0 32px 28px;">
                              <p style="margin:0;font-size:13px;line-height:1.7;color:#6b7280;">
                                请勿将验证码告诉任何人。DinQorAI 不会通过电话、短信或邮件向您索要验证码；若非本人操作，请忽略本邮件。
                              </p>
                            </td>
                          </tr>
                          <tr>
                            <td style="background:#f9fafb;border-top:1px solid #e5e7eb;padding:16px 32px;text-align:center;font-size:12px;color:#9ca3af;">
                              本邮件由 DinQorAI 自动发送，请勿直接回复
                            </td>
                          </tr>
                        </table>
                      </td>
                    </tr>
                  </table>
                </body>
                </html>
                """.formatted(FONT, CODE_EXPIRE_MINUTES, digits);
    }

    private static String buildDigitCells(String code) {
        StringBuilder sb = new StringBuilder();
        char[] chars = code.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            String digit = HtmlUtils.htmlEscape(String.valueOf(chars[i]));
            sb.append("""
                    <span style="display:inline-block;min-width:38px;margin:0 3px;font-size:28px;line-height:48px;text-align:center;font-weight:700;color:#111827;font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;">
                      %s
                    </span>
                    """.formatted(digit));
        }
        if (chars.length == 0) {
            sb.append("""
                    <span style="display:inline-block;padding:12px 18px;font-size:22px;font-weight:700;color:#111827;letter-spacing:0.35em;">
                      ------
                    </span>
                    """);
        }
        return sb.toString();
    }
}
