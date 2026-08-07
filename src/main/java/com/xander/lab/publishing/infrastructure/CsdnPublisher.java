package com.xander.lab.publishing.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.xander.lab.publishing.domain.BlogPublisher;
import com.xander.lab.publishing.domain.PublishCommand;
import com.xander.lab.publishing.domain.PublishResult;
import com.xander.lab.publishing.application.CsdnCredentialService;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
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
    // Public web-console signing secret embedded in CSDN's current mp_v3 frontend bundle.
    private static final String WEB_CONSOLE_SECRET = "9znpamsyl2c7cdrr9sas0le9vbc3r6ba";
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
        htmlContent = imageHtmlRewriter.rewrite(htmlContent, cookie, properties.getApiKey(), String.valueOf(command.taskId()));
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
                        java.util.Map.entry("description", command.summary() == null ? "" : command.summary()),
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
                        java.util.Map.entry("cover_images", java.util.List.of()),
                        java.util.Map.entry("cover_type", 0),
                        java.util.Map.entry("vote_id", 0),
                        java.util.Map.entry("resource_id", ""),
                        java.util.Map.entry("is_new", 1),
                        java.util.Map.entry("sync_git_code", 0)))
                .retrieve().body(JsonNode.class);
        if (response == null || response.path("code").asInt(0) != 200) {
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
    }

    private String sign(String apiKey, String nonce) {
        String canonical = "POST\n" + ACCEPT + "\n\n" + CONTENT_TYPE + "\n\n"
                + "x-ca-key:" + apiKey + "\n"
                + "x-ca-nonce:" + nonce + "\n"
                + SAVE_PATH;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(WEB_CONSOLE_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to sign CSDN request", e);
        }
    }
}
