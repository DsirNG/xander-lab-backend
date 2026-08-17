package com.xander.lab.integration.mcp.csdn.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.xander.lab.publishing.domain.BlogPublisher;
import com.xander.lab.publishing.domain.PublishCommand;
import com.xander.lab.publishing.domain.PublishResult;
import com.xander.lab.publishing.domain.PublishingLimits;
import com.xander.lab.integration.mcp.csdn.application.CsdnCredentialService;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.List;
import java.util.stream.Collectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Publishes an article through CSDN's web console API. */
@Component
@Slf4j
public class CsdnPublisher implements BlogPublisher {
    private static final String SAVE_PATH = "/blog-console-api/v1/postedit/saveArticle";
    private static final String ACCEPT = "application/json, text/plain, */*";
    private static final String CONTENT_TYPE = "application/json;";
    private final CsdnProperties properties;
    private final CsdnCredentialService credentials;
    private final CsdnImageHtmlRewriter imageHtmlRewriter;
    private final RestClient client;

    public CsdnPublisher(CsdnProperties properties, CsdnCredentialService credentials, CsdnImageHtmlRewriter imageHtmlRewriter) {
        this.properties = properties;
        this.credentials = credentials;
        this.imageHtmlRewriter = imageHtmlRewriter;
        this.client = RestClient.builder().baseUrl(properties.getBaseUrl()).build();
    }

    @Override public String platform() { return "csdn"; }

    @Override
    public PublishResult publish(PublishCommand command) {
        if (!properties.isEnabled()) throw new IllegalStateException("CSDN publisher is disabled");
        String cookie = credentials.requireCookie(command.userId());
        String nonce = UUID.randomUUID().toString();
        String signature = sign(properties.getApiKey(), nonce);
        String htmlContent = CsdnMarkdownConverter.toHtml(command.content());
        // CSDN sync uses a negative publication task id to isolate its record;
        // the backlink must always point to the real positive blog post id.
        long blogPostId = Math.abs(command.taskId());
        String originalUrl = "https://dinqor.cn/blog/" + blogPostId;
        htmlContent = imageHtmlRewriter.rewrite(htmlContent, cookie, properties.getApiKey(), String.valueOf(command.taskId()), originalUrl);
        List<String> coverImages = resolveCoverImages(command.coverImage(), htmlContent);
        try {
            JsonNode response = client.post().uri(SAVE_PATH)
                    .header("Accept", ACCEPT)
                    .header("Content-Type", CONTENT_TYPE)
                    .header("Cookie", cookie)
                    .header("origin", "https://mp.csdn.net")
                    .header("referer", "https://mp.csdn.net/")
                    .header("x-ca-key", properties.getApiKey())
                    .header("x-ca-nonce", nonce)
                    .header("x-ca-signature-headers", "x-ca-key,x-ca-nonce")
                    .header("x-ca-signature", signature)
                    .body(java.util.Map.ofEntries(
                            java.util.Map.entry("article_id", ""),
                            java.util.Map.entry("title", command.title()),
                            java.util.Map.entry("description", normalizeDescription(command.summary())),
                            java.util.Map.entry("content", htmlContent),
                            java.util.Map.entry("tags", command.tags().stream().collect(Collectors.joining(","))),
                            java.util.Map.entry("categories", command.categoryId() == null ? "" : command.categoryId()),
                            java.util.Map.entry("type", "original"),
                            java.util.Map.entry("status", 0),
                            java.util.Map.entry("read_type", "public"),
                            java.util.Map.entry("authorized_status", true),
                            java.util.Map.entry("original_link", ""),
                            java.util.Map.entry("source", "pc_mdeditor"),
                            java.util.Map.entry("not_auto_saved", 0),
                            java.util.Map.entry("cover_images", coverImages),
                            java.util.Map.entry("cover_type", 0),
                            java.util.Map.entry("vote_id", 0),
                            java.util.Map.entry("resource_id", ""),
                            java.util.Map.entry("is_new", 1),
                            java.util.Map.entry("sync_git_code", 0)))
                    .retrieve().body(JsonNode.class);
            if (response == null || response.path("code").asInt(0) != 200) {
                markExpiredIfSessionInvalid(command.userId(), 200, response);
                throw new IllegalStateException("CSDN publish failed: " + (response == null ? "empty response" : response.path("msg").asText("unknown error")));
            }
            JsonNode data = response.path("data");
            if (data.isTextual() && "成功".equals(data.asText())) {
                return new PublishResult(platform(), "accepted:" + command.taskId(), "");
            }
            String articleId = data.path("article_id").asText("");
            String url = data.path("url").asText("");
            if (articleId.isBlank() || url.isBlank()) {
                log.warn("[CSDN] Publish returned incomplete result: code={}, msg={}, data={}",
                        response.path("code").asText(), response.path("msg").asText(), data);
                throw new IllegalStateException("CSDN publish returned no article result: "
                        + response.path("msg").asText("no message"));
            }
            return new PublishResult(platform(), articleId, url);
        } catch (RestClientResponseException ex) {
            markExpiredIfSessionInvalid(command.userId(), ex.getStatusCode().value(), null);
            throw ex;
        }
    }

    /**
     * CSDN 返回登录态失效（HTTP 401 或响应体 code=401）时，把该用户授权标记为 EXPIRED，
     * 前端授权面板据此展示「待重新授权」；其余错误不改变授权状态。
     */
    void markExpiredIfSessionInvalid(Long userId, int httpStatus, JsonNode body) {
        if (!isSessionExpired(httpStatus, body)) return;
        credentials.markExpired(userId);
        log.warn("[CSDN] CSDN 会话已失效（HTTP {}），用户 {} 的授权被标记为待重新授权", httpStatus, userId);
    }

    static boolean isSessionExpired(int httpStatus, JsonNode body) {
        if (httpStatus == 401) return true;
        return body != null && body.path("code").asInt(0) == 401;
    }

        /**
     * 摘要统一按两个外部平台中最严格的上限处理：截断到 {@link PublishingLimits#MAX_SUMMARY_LENGTH}
     * 字，保证同一份数据对 CSDN（200 字内）与掘金（50-100 字）都合法。
     */
    static String normalizeDescription(String summary) {
        if (summary == null) return "";
        String trimmed = summary.strip();
        return trimmed.length() > PublishingLimits.MAX_SUMMARY_LENGTH
                ? trimmed.substring(0, PublishingLimits.MAX_SUMMARY_LENGTH) : trimmed;
    }

    static List<String> resolveCoverImages(String coverImage, String htmlContent) {
        if (coverImage != null && !coverImage.isBlank()) {
            return List.of(coverImage.trim());
        }
        return extractCoverImages(htmlContent);
    }

    static List<String> extractCoverImages(String htmlContent) {
        if (htmlContent == null || htmlContent.isBlank()) {
            return List.of();
        }
        var firstImageElement = Jsoup.parseBodyFragment(htmlContent).selectFirst("img[src]");
        String firstImage = firstImageElement == null ? "" : firstImageElement.attr("src").trim();
        return firstImage.isBlank() ? List.of() : List.of(firstImage);
    }

    private String sign(String apiKey, String nonce) {
        String canonical = "POST\n" + ACCEPT + "\n\n" + CONTENT_TYPE + "\n\n"
                + "x-ca-key:" + apiKey + "\n"
                + "x-ca-nonce:" + nonce + "\n"
                + SAVE_PATH;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getWebConsoleSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to sign CSDN request", e);
        }
    }
}
