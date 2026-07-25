package com.xander.lab.service;

import org.springframework.web.util.HtmlUtils;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Builds branded HTML wrappers for scheduled reminder emails.
 * Supports PLAIN (escaped) and HTML (rendered) body content.
 */
public final class EmailReminderMailTemplates {

    public static final String CONTENT_PLAIN = "PLAIN";
    public static final String CONTENT_HTML = "HTML";

    public static final String TEMPLATE_CLASSIC = "classic";
    public static final String TEMPLATE_MINIMAL = "minimal";
    public static final String TEMPLATE_CARD = "card";
    public static final String TEMPLATE_NOTICE = "notice";

    private static final Set<String> TEMPLATES = Set.of(
            TEMPLATE_CLASSIC, TEMPLATE_MINIMAL, TEMPLATE_CARD, TEMPLATE_NOTICE
    );

    private static final Pattern SCRIPT_TAG = Pattern.compile(
            "(?is)<script\\b[^>]*>.*?</script\\s*>"
    );
    private static final Pattern STYLE_TAG = Pattern.compile(
            "(?is)<style\\b[^>]*>.*?</style\\s*>"
    );
    private static final Pattern DANGEROUS_TAG = Pattern.compile(
            "(?is)</?(?:iframe|object|embed|link|meta|base|form|input|button|textarea|select)\\b[^>]*>"
    );
    private static final Pattern EVENT_HANDLER = Pattern.compile(
            "(?is)\\s+on[a-z]+\\s*=\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s>]+)"
    );
    private static final Pattern JS_URL = Pattern.compile(
            "(?is)\\b(href|src)\\s*=\\s*([\"']?)\\s*javascript:"
    );

    private EmailReminderMailTemplates() {
    }

    public static String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return CONTENT_PLAIN;
        }
        String normalized = contentType.strip().toUpperCase(Locale.ROOT);
        return CONTENT_HTML.equals(normalized) ? CONTENT_HTML : CONTENT_PLAIN;
    }

    public static String normalizeTemplateId(String templateId) {
        if (templateId == null || templateId.isBlank()) {
            return TEMPLATE_CLASSIC;
        }
        String normalized = templateId.strip().toLowerCase(Locale.ROOT);
        return TEMPLATES.contains(normalized) ? normalized : TEMPLATE_CLASSIC;
    }

    public static String resolveBodyHtml(String userMessage, String contentType) {
        String message = userMessage == null ? "" : userMessage;
        if (CONTENT_HTML.equals(normalizeContentType(contentType))) {
            return sanitizeHtml(message);
        }
        return HtmlUtils.htmlEscape(message)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace("\n", "<br>");
    }

    public static String resolvePlainText(String userMessage, String contentType,
                                          String formattedTime, String displayZone) {
        String message = userMessage == null ? "" : userMessage;
        String body = CONTENT_HTML.equals(normalizeContentType(contentType))
                ? stripTags(message)
                : message;
        return "Xander Lab 定时提醒\n\n"
                + body.strip()
                + "\n\n计划发送时间：" + formattedTime + " (" + displayZone + ")";
    }

    public static String buildHtml(String templateId, String subject, String bodyHtml,
                                   String formattedTime, String displayZone) {
        String template = normalizeTemplateId(templateId);
        String escapedSubject = HtmlUtils.htmlEscape(subject == null ? "" : subject);
        String escapedTime = HtmlUtils.htmlEscape(formattedTime == null ? "" : formattedTime);
        String escapedZone = HtmlUtils.htmlEscape(displayZone == null ? "" : displayZone);
        String safeBody = bodyHtml == null ? "" : bodyHtml;

        return switch (template) {
            case TEMPLATE_MINIMAL -> buildMinimal(escapedSubject, safeBody, escapedTime, escapedZone);
            case TEMPLATE_CARD -> buildCard(escapedSubject, safeBody, escapedTime, escapedZone);
            case TEMPLATE_NOTICE -> buildNotice(escapedSubject, safeBody, escapedTime, escapedZone);
            default -> buildClassic(escapedSubject, safeBody, escapedTime, escapedZone);
        };
    }

    static String sanitizeHtml(String html) {
        String cleaned = SCRIPT_TAG.matcher(html).replaceAll("");
        cleaned = STYLE_TAG.matcher(cleaned).replaceAll("");
        cleaned = DANGEROUS_TAG.matcher(cleaned).replaceAll("");
        cleaned = EVENT_HANDLER.matcher(cleaned).replaceAll("");
        cleaned = JS_URL.matcher(cleaned).replaceAll("$1=$2#blocked:");
        return cleaned;
    }

    private static String stripTags(String html) {
        return html
                .replaceAll("(?is)<br\\s*/?>", "\n")
                .replaceAll("(?is)</p\\s*>", "\n")
                .replaceAll("(?is)<[^>]+>", "")
                .replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }

    private static String document(String title, String bodyInner) {
        return "<!doctype html><html lang=\"zh-CN\"><head>"
                + "<meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + title + "</title></head>"
                + bodyInner
                + "</html>";
    }

    private static String footer(String escapedTime, String escapedZone) {
        return "<div style=\"margin-top:22px;padding-top:20px;border-top:1px solid #e8edf5;"
                + "font-size:13px;line-height:1.7;color:#7b879b;\">"
                + "这是你在 Xander Lab 设置的定时邮件提醒。<br>"
                + "计划发送时间：" + escapedTime + "（" + escapedZone + "）"
                + "</div>";
    }

    private static String buildClassic(String subject, String body, String time, String zone) {
        String inner = "<body style=\"margin:0;padding:0;background:#f4f7fb;color:#172033;"
                + "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif;\">"
                + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" "
                + "style=\"background:#f4f7fb;padding:32px 12px;\"><tr><td align=\"center\">"
                + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" "
                + "style=\"max-width:620px;background:#ffffff;border-radius:20px;overflow:hidden;"
                + "box-shadow:0 16px 40px rgba(31,55,90,.10);\">"
                + "<tr><td style=\"padding:30px 34px;background:linear-gradient(135deg,#172554,#2563eb);color:#fff;\">"
                + "<div style=\"font-size:13px;letter-spacing:2px;opacity:.82;\">XANDER LAB</div>"
                + "<h1 style=\"margin:10px 0 0;font-size:26px;line-height:1.35;\">"
                + subject + "</h1></td></tr>"
                + "<tr><td style=\"padding:34px;\">"
                + "<div style=\"font-size:13px;font-weight:700;color:#2563eb;letter-spacing:.08em;\">邮件正文</div>"
                + "<div style=\"margin-top:14px;padding:22px;background:#f8fafc;border:1px solid #e2e8f0;"
                + "border-radius:14px;font-size:16px;line-height:1.85;color:#263348;word-break:break-word;\">"
                + body + "</div>"
                + footer(time, zone)
                + "</td></tr></table>"
                + "<div style=\"margin-top:18px;font-size:12px;color:#98a2b3;\">由 Xander Lab 定时提醒服务发送</div>"
                + "</td></tr></table></body>";
        return document(subject, inner);
    }

    private static String buildMinimal(String subject, String body, String time, String zone) {
        String inner = "<body style=\"margin:0;padding:0;background:#fafafa;color:#111;"
                + "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif;\">"
                + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" "
                + "style=\"padding:28px 12px;\"><tr><td align=\"center\">"
                + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" "
                + "style=\"max-width:560px;background:#fff;border:1px solid #e5e7eb;\">"
                + "<tr><td style=\"border-top:4px solid #0f172a;padding:28px 28px 8px;\">"
                + "<div style=\"font-size:12px;color:#64748b;letter-spacing:.12em;\">XANDER LAB</div>"
                + "<h1 style=\"margin:12px 0 0;font-size:22px;font-weight:700;color:#0f172a;\">"
                + subject + "</h1></td></tr>"
                + "<tr><td style=\"padding:8px 28px 28px;font-size:15px;line-height:1.8;color:#334155;word-break:break-word;\">"
                + body
                + footer(time, zone)
                + "</td></tr></table>"
                + "<div style=\"margin-top:14px;font-size:12px;color:#94a3b8;\">Xander Lab</div>"
                + "</td></tr></table></body>";
        return document(subject, inner);
    }

    private static String buildCard(String subject, String body, String time, String zone) {
        String inner = "<body style=\"margin:0;padding:0;background:#fff7ed;color:#1c1917;"
                + "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif;\">"
                + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" "
                + "style=\"padding:32px 12px;\"><tr><td align=\"center\">"
                + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" "
                + "style=\"max-width:580px;background:#ffffff;border-radius:16px;border:1px solid #fed7aa;"
                + "box-shadow:0 10px 30px rgba(154,52,18,.08);\">"
                + "<tr><td style=\"padding:26px 30px;background:#ea580c;color:#fff;border-radius:16px 16px 0 0;\">"
                + "<div style=\"font-size:12px;opacity:.85;\">定时提醒 · Xander Lab</div>"
                + "<h1 style=\"margin:8px 0 0;font-size:24px;\">" + subject + "</h1></td></tr>"
                + "<tr><td style=\"padding:28px 30px;font-size:16px;line-height:1.85;color:#292524;word-break:break-word;\">"
                + body
                + footer(time, zone)
                + "</td></tr></table></td></tr></table></body>";
        return document(subject, inner);
    }

    private static String buildNotice(String subject, String body, String time, String zone) {
        String inner = "<body style=\"margin:0;padding:0;background:#ecfdf5;color:#064e3b;"
                + "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif;\">"
                + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" "
                + "style=\"padding:32px 12px;\"><tr><td align=\"center\">"
                + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" "
                + "style=\"max-width:600px;background:#ffffff;border-radius:12px;border:1px solid #a7f3d0;\">"
                + "<tr><td style=\"padding:24px 28px;background:#059669;color:#fff;\">"
                + "<div style=\"font-size:12px;letter-spacing:.08em;opacity:.9;\">NOTICE</div>"
                + "<h1 style=\"margin:8px 0 0;font-size:22px;\">" + subject + "</h1></td></tr>"
                + "<tr><td style=\"padding:26px 28px;font-size:15px;line-height:1.8;color:#065f46;word-break:break-word;\">"
                + "<div style=\"padding:16px 18px;background:#f0fdf4;border-left:4px solid #10b981;border-radius:0 8px 8px 0;\">"
                + body + "</div>"
                + footer(time, zone)
                + "</td></tr></table>"
                + "<div style=\"margin-top:14px;font-size:12px;color:#6ee7b7;\">Xander Lab 通知邮件</div>"
                + "</td></tr></table></body>";
        return document(subject, inner);
    }
}
