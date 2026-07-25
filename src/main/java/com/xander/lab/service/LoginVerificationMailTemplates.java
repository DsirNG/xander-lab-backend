package com.xander.lab.service;

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
        return "【Xander Lab】登录验证码";
    }

    public static String buildPlainText(String code) {
        String safeCode = code == null ? "" : code.strip();
        return """
                Xander Lab 登录验证码

                您正在登录 Xander Lab 平台，请使用以下验证码完成身份验证：

                %s

                有效期：%d 分钟（超时请重新获取）

                安全提醒：
                · 验证码仅用于本次登录，请勿转发本邮件或告知他人
                · Xander Lab 不会以任何方式向您索要验证码
                · 如非本人操作，请忽略本邮件，账号仍然安全
                · 请勿在不明链接或第三方页面输入该验证码

                — Xander Lab 安全中心
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
                  <title>Xander Lab 登录验证码</title>
                  <style>
                    @keyframes xl-code-float {
                      0%%, 100%% { transform: translateY(0); }
                      50%% { transform: translateY(-6px); }
                    }
                    @keyframes xl-code-glow {
                      0%%, 100%% { box-shadow: 0 8px 20px rgba(51,102,255,0.18); }
                      50%% { box-shadow: 0 12px 28px rgba(51,102,255,0.32); }
                    }
                    .xl-digit {
                      display: inline-block;
                      animation: xl-code-float 1.6s ease-in-out infinite;
                    }
                    .xl-digit-box {
                      animation: xl-code-glow 1.6s ease-in-out infinite;
                    }
                    .xl-d1 { animation-delay: 0s; }
                    .xl-d2 { animation-delay: 0.12s; }
                    .xl-d3 { animation-delay: 0.24s; }
                    .xl-d4 { animation-delay: 0.36s; }
                    .xl-d5 { animation-delay: 0.48s; }
                    .xl-d6 { animation-delay: 0.60s; }
                  </style>
                </head>
                <body style="margin:0;padding:0;background:#eef2f7;font-family:%s;">
                  <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background:#eef2f7;padding:28px 12px;">
                    <tr>
                      <td align="center">
                        <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="max-width:560px;background:#ffffff;border-radius:16px;overflow:hidden;border:1px solid #dbe3ef;">
                          <tr>
                            <td style="background:linear-gradient(135deg,#1d4ed8 0%%,#3366ff 55%%,#0ea5e9 100%%);padding:28px 28px 24px;">
                              <div style="font-size:12px;letter-spacing:0.22em;font-weight:800;color:rgba(255,255,255,0.85);text-transform:uppercase;">
                                XANDER LAB
                              </div>
                              <div style="margin-top:10px;font-size:24px;line-height:1.3;font-weight:800;color:#ffffff;">
                                登录验证码
                              </div>
                              <div style="margin-top:8px;font-size:14px;line-height:1.6;color:rgba(255,255,255,0.9);">
                                此邮件由 <strong style="color:#fff;">Xander Lab</strong> 平台安全中心发出，用于完成邮箱登录验证。
                              </div>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:28px 28px 8px;">
                              <p style="margin:0;font-size:15px;line-height:1.7;color:#334155;">
                                您好，请在登录页输入下方验证码。验证码将在
                                <strong style="color:#1d4ed8;">%d 分钟</strong>
                                后失效。
                              </p>
                            </td>
                          </tr>
                          <tr>
                            <td align="center" style="padding:18px 24px 10px;">
                              <div style="font-size:12px;font-weight:700;letter-spacing:0.16em;color:#64748b;text-transform:uppercase;margin-bottom:12px;">
                                您的验证码
                              </div>
                              <div style="display:inline-block;padding:18px 16px;border-radius:16px;background:linear-gradient(180deg,#f8fbff 0%%,#eef4ff 100%%);border:1px solid #c7d7fe;">
                                %s
                              </div>
                              <div style="margin-top:14px;font-size:13px;color:#64748b;">
                                请尽快完成验证 · 切勿泄露给他人
                              </div>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:18px 28px 8px;">
                              <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background:#fff7ed;border:1px solid #fed7aa;border-radius:12px;">
                                <tr>
                                  <td style="padding:16px 18px;">
                                    <div style="font-size:14px;font-weight:800;color:#9a3412;margin-bottom:10px;">
                                      安全提示
                                    </div>
                                    <ul style="margin:0;padding-left:18px;color:#9a3412;font-size:13px;line-height:1.75;">
                                      <li>验证码仅用于本次 Xander Lab 登录，请勿转发本邮件。</li>
                                      <li>Xander Lab 工作人员不会以电话、短信或邮件向您索要验证码。</li>
                                      <li>如非本人发起登录，请忽略本邮件；验证码过期后即失效。</li>
                                      <li>请勿在陌生网站、不明弹窗或第三方应用中输入该验证码。</li>
                                      <li>保护账号安全：不要截图分享验证码，也不要告诉任何人。</li>
                                    </ul>
                                  </td>
                                </tr>
                              </table>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:18px 28px 28px;">
                              <p style="margin:0;font-size:12px;line-height:1.7;color:#94a3b8;">
                                若您并未请求登录 Xander Lab，可安全忽略此邮件。账号在未输入正确验证码前不会被登录。
                              </p>
                            </td>
                          </tr>
                          <tr>
                            <td style="background:#f8fafc;border-top:1px solid #e2e8f0;padding:16px 28px;text-align:center;">
                              <div style="font-size:12px;font-weight:800;letter-spacing:0.14em;color:#64748b;">
                                XANDER LAB
                              </div>
                              <div style="margin-top:6px;font-size:11px;color:#94a3b8;">
                                本邮件由系统自动发送，请勿直接回复
                              </div>
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
            int delayClass = Math.min(i + 1, 6);
            sb.append("""
                    <span class="xl-digit xl-d%d" style="display:inline-block;margin:0 4px;vertical-align:top;">
                      <span class="xl-digit-box" style="display:inline-block;min-width:42px;height:56px;line-height:56px;text-align:center;border-radius:12px;background:#ffffff;border:1px solid #93c5fd;font-size:28px;font-weight:800;letter-spacing:0;color:#1d4ed8;font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;">
                        %s
                      </span>
                    </span>
                    """.formatted(delayClass, digit));
        }
        if (chars.length == 0) {
            sb.append("""
                    <span style="display:inline-block;padding:12px 18px;border-radius:12px;background:#fff;border:1px solid #93c5fd;font-size:22px;font-weight:800;color:#1d4ed8;letter-spacing:0.35em;">
                      ------
                    </span>
                    """);
        }
        return sb.toString();
    }
}
