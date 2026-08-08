package com.xander.lab.automation.domain;

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
    /** No layout wrapper — send user HTML/body as-is (sanitized). */
    public static final String TEMPLATE_NONE = "none";

    private static final Set<String> TEMPLATES = Set.of(
            TEMPLATE_CLASSIC, TEMPLATE_MINIMAL, TEMPLATE_CARD, TEMPLATE_NOTICE, TEMPLATE_NONE
    );

    private static final Pattern HTML_TAG = Pattern.compile("(?is)</?[a-z][^>]*>");
    private static final Pattern FULL_HTML_DOC = Pattern.compile("(?is)<!doctype\\s+html|<html\\b");


    private static final String FONT =
            "-apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC','Hiragino Sans GB','Microsoft YaHei',sans-serif";

    private static final Pattern SCRIPT_TAG = Pattern.compile(
            "(?is)<script\\b[^>]*>.*?</script\\s*>"
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
            return TEMPLATE_NONE;
        }
        String normalized = templateId.strip().toLowerCase(Locale.ROOT);
        return TEMPLATES.contains(normalized) ? normalized : TEMPLATE_NONE;
    }

    public static boolean looksLikeHtml(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        return HTML_TAG.matcher(message).find();
    }

    public static boolean looksLikeFullHtmlDocument(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        return FULL_HTML_DOC.matcher(message).find();
    }

    /**
     * Prefer explicit contentType; otherwise treat as HTML when markup is present
     * or a layout template is selected.
     */
    public static String resolveEffectiveContentType(String contentType, String templateId, String message) {
        String explicit = contentType == null ? "" : contentType.strip();
        if (!explicit.isEmpty()) {
            return normalizeContentType(explicit);
        }
        String template = normalizeTemplateId(templateId);
        if (!TEMPLATE_NONE.equals(template) || looksLikeHtml(message)) {
            return CONTENT_HTML;
        }
        return CONTENT_PLAIN;
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
        if (CONTENT_PLAIN.equals(normalizeContentType(contentType))) {
            return message;
        }
        String body = stripTags(message);
        String templateHint = formattedTime == null || formattedTime.isBlank()
                ? body.strip()
                : body.strip() + "\n\n计划发送时间：" + formattedTime
                + (displayZone == null || displayZone.isBlank() ? "" : " (" + displayZone + ")");
        return templateHint;
    }

    public static String buildHtml(String templateId, String subject, String bodyHtml,
                                   String formattedTime, String displayZone) {
        String template = normalizeTemplateId(templateId);
        String escapedSubject = HtmlUtils.htmlEscape(subject == null ? "" : subject);
        String escapedTime = HtmlUtils.htmlEscape(formattedTime == null ? "" : formattedTime);
        String escapedZone = HtmlUtils.htmlEscape(displayZone == null ? "" : displayZone);
        String safeBody = bodyHtml == null ? "" : bodyHtml;

        // Full custom HTML documents are sent without an outer layout wrapper.
        if (looksLikeFullHtmlDocument(safeBody)) {
            return sanitizeHtml(safeBody);
        }

        return switch (template) {
            case TEMPLATE_NONE -> buildRaw(escapedSubject, safeBody);
            case TEMPLATE_MINIMAL -> buildMinimal(escapedSubject, safeBody, escapedTime, escapedZone);
            case TEMPLATE_CARD -> buildCard(escapedSubject, safeBody, escapedTime, escapedZone);
            case TEMPLATE_NOTICE -> buildNotice(escapedSubject, safeBody, escapedTime, escapedZone);
            default -> buildClassic(escapedSubject, safeBody, escapedTime, escapedZone);
        };
    }

    static String sanitizeHtml(String html) {
        String cleaned = SCRIPT_TAG.matcher(html).replaceAll("");
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

    /** Custom HTML fragment without branded layout. */
    private static String buildRaw(String subject, String body) {
        String inner = "<body style=\"margin:0;padding:24px;background:#ffffff;color:#111;"
                + "font-family:" + FONT + ";font-size:15px;line-height:1.75;\">"
                + body
                + "</body>";
        return document(subject, inner);
    }

    private static String metaRow(String time, String zone) {
        return "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" "
                + "style=\"margin-top:28px;\"><tr>"
                + "<td style=\"padding-top:18px;border-top:1px solid #e8e4dc;font-size:12px;"
                + "line-height:1.7;color:#8a8478;\">"
                + "计划发送 · " + time + " · " + zone
                + "</td></tr></table>";
    }

    /** 晨光信笺：柔和纸感 + 靛蓝竖线 */
    private static String buildClassic(String subject, String body, String time, String zone) {
        String inner = "<body style=\"margin:0;padding:0;background:#eef2f6;color:#1a2332;"
                + "font-family:" + FONT + ";\">"
                + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" "
                + "style=\"background:#eef2f6;padding:40px 14px;\"><tr><td align=\"center\">"
                + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" "
                + "style=\"max-width:600px;background:#fffcf7;border-radius:4px;"
                + "box-shadow:0 18px 50px rgba(26,35,50,.08);\">"
                + "<tr><td style=\"height:6px;background:#1d4ed8;font-size:0;line-height:0;\">&nbsp;</td></tr>"
                + "<tr><td style=\"padding:36px 40px 12px;\">"
                + "<div style=\"font-size:11px;letter-spacing:.22em;color:#1d4ed8;font-weight:700;\">XANDER LAB</div>"
                + "<h1 style=\"margin:18px 0 0;font-size:28px;line-height:1.3;font-weight:700;color:#0f172a;"
                + "letter-spacing:-.02em;\">" + subject + "</h1>"
                + "</td></tr>"
                + "<tr><td style=\"padding:8px 40px 36px;\">"
                + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\">"
                + "<tr><td style=\"width:3px;background:#93c5fd;border-radius:2px;\"></td>"
                + "<td style=\"padding-left:18px;font-size:16px;line-height:1.85;color:#334155;word-break:break-word;\">"
                + body + "</td></tr></table>"
                + metaRow(time, zone)
                + "</td></tr></table>"
                + "<div style=\"margin-top:16px;font-size:11px;letter-spacing:.04em;color:#94a3b8;\">"
                + "Xander Lab · Scheduled Mail</div>"
                + "</td></tr></table></body>";
        return document(subject, inner);
    }

    /** 纸间留白：大量留白 + 细墨线 */
    private static String buildMinimal(String subject, String body, String time, String zone) {
        String inner = "<body style=\"margin:0;padding:0;background:#f3f1ec;color:#171717;"
                + "font-family:" + FONT + ";\">"
                + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" "
                + "style=\"padding:48px 14px;\"><tr><td align=\"center\">"
                + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" "
                + "style=\"max-width:540px;background:#ffffff;\">"
                + "<tr><td style=\"padding:42px 36px 10px;\">"
                + "<div style=\"width:36px;height:3px;background:#171717;\"></div>"
                + "<div style=\"margin-top:22px;font-size:11px;letter-spacing:.18em;color:#737373;\">XANDER LAB</div>"
                + "<h1 style=\"margin:14px 0 0;font-size:30px;line-height:1.25;font-weight:600;color:#0a0a0a;"
                + "letter-spacing:-.03em;\">" + subject + "</h1>"
                + "</td></tr>"
                + "<tr><td style=\"padding:10px 36px 42px;\">"
                + "<div style=\"margin:22px 0;height:1px;background:#e5e5e5;\"></div>"
                + "<div style=\"font-size:16px;line-height:1.9;color:#404040;word-break:break-word;\">"
                + body + "</div>"
                + "<div style=\"margin-top:34px;font-size:12px;color:#a3a3a3;letter-spacing:.02em;\">"
                + time + " · " + zone + "</div>"
                + "</td></tr></table></td></tr></table></body>";
        return document(subject, inner);
    }

    /** 落日卡片：珊瑚暖调 hero + 白底正文 */
    private static String buildCard(String subject, String body, String time, String zone) {
        String inner = "<body style=\"margin:0;padding:0;background:#f6ebe3;color:#1c1917;"
                + "font-family:" + FONT + ";\">"
                + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" "
                + "style=\"padding:40px 14px;\"><tr><td align=\"center\">"
                + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" "
                + "style=\"max-width:580px;background:#ffffff;border-radius:24px;overflow:hidden;"
                + "box-shadow:0 20px 48px rgba(120,53,15,.12);\">"
                + "<tr><td style=\"padding:34px 34px 28px;background:linear-gradient(145deg,#fb7185 0%,#f97316 100%);"
                + "color:#fff;\">"
                + "<div style=\"display:inline-block;padding:5px 10px;border:1px solid rgba(255,255,255,.45);"
                + "border-radius:999px;font-size:11px;letter-spacing:.12em;\">REMINDER</div>"
                + "<h1 style=\"margin:18px 0 0;font-size:26px;line-height:1.3;font-weight:700;\">"
                + subject + "</h1>"
                + "</td></tr>"
                + "<tr><td style=\"padding:30px 34px 34px;\">"
                + "<div style=\"font-size:16px;line-height:1.85;color:#44403c;word-break:break-word;\">"
                + body + "</div>"
                + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" "
                + "style=\"margin-top:26px;background:#fff7ed;border-radius:14px;\">"
                + "<tr><td style=\"padding:14px 16px;font-size:12px;line-height:1.6;color:#9a3412;\">"
                + "发送时刻<br><span style=\"font-size:14px;font-weight:700;color:#7c2d12;\">"
                + time + "</span>"
                + "<span style=\"color:#c2410c;\"> · " + zone + "</span>"
                + "</td></tr></table>"
                + "</td></tr></table>"
                + "<div style=\"margin-top:16px;font-size:11px;color:#b08968;\">Xander Lab</div>"
                + "</td></tr></table></body>";
        return document(subject, inner);
    }

    /** 青石通知：深青石板头图 + 浅青内容区 */
    private static String buildNotice(String subject, String body, String time, String zone) {
        String inner = "<body style=\"margin:0;padding:0;background:#e7eef0;color:#134e4a;"
                + "font-family:" + FONT + ";\">"
                + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" "
                + "style=\"padding:40px 14px;\"><tr><td align=\"center\">"
                + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" "
                + "style=\"max-width:590px;background:#ffffff;border-radius:18px;overflow:hidden;"
                + "border:1px solid #c5d5d8;\">"
                + "<tr><td style=\"padding:30px 32px;background:#0f766e;color:#ecfdf5;\">"
                + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\"><tr>"
                + "<td style=\"font-size:11px;letter-spacing:.2em;opacity:.85;\">XANDER LAB</td>"
                + "<td align=\"right\" style=\"font-size:11px;opacity:.75;\">NOTICE</td>"
                + "</tr></table>"
                + "<h1 style=\"margin:16px 0 0;font-size:24px;line-height:1.35;font-weight:700;color:#fff;\">"
                + subject + "</h1>"
                + "</td></tr>"
                + "<tr><td style=\"padding:28px 32px 32px;\">"
                + "<div style=\"padding:20px 22px;background:#f0fdfa;border-radius:14px;"
                + "border:1px solid #ccfbf1;font-size:15px;line-height:1.85;color:#115e59;word-break:break-word;\">"
                + body + "</div>"
                + "<div style=\"margin-top:22px;font-size:12px;line-height:1.7;color:#5f7a78;\">"
                + "计划发送时间：" + time + "<br>时区：" + zone
                + "</div>"
                + "</td></tr></table>"
                + "<div style=\"margin-top:14px;font-size:11px;color:#7a9694;\">由 Xander Lab 发送</div>"
                + "</td></tr></table></body>";
        return document(subject, inner);
    }
}
