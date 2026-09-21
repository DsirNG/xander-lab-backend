package com.xander.lab.integration.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.xander.lab.integration.api.BlogSyncResult;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Platform-owned CSDN HTML conversion, image hosting, and article publication workflow. */
@Component
@ConditionalOnProperty(prefix = "platform-integration", name = "authorization-enabled",
        havingValue = "true")
public class PlatformCsdnPublisher {

    private final PlatformCsdnCredentialStore credentials;
    private final PlatformCsdnClient client;

    public PlatformCsdnPublisher(PlatformCsdnCredentialStore credentials,
                                 PlatformCsdnClient client) {
        this.credentials = credentials;
        this.client = client;
    }

    public BlogSyncResult publish(PublishRequest request) {
        validate(request);
        PlatformCsdnClient.Credential credential =
                new PlatformCsdnClient.Credential(credentials.requireCookie(request.userId()));
        try {
            String originalUrl = "https://dinqor.cn/blog/" + request.postId();
            String html = rewriteImages(PlatformCsdnMarkdownConverter.toHtml(request.content()),
                    credential, String.valueOf(-request.postId()), originalUrl);
            List<String> coverImages = resolveCoverImages(request.coverImage(), html);
            JsonNode response = client.saveArticle(credential, new PlatformCsdnClient.Article(
                    request.title(), normalizeDescription(request.summary()), html, request.tags(),
                    request.category(), coverImages));
            JsonNode data = response.path("data");
            if (data.isTextual() && "成功".equals(data.asText())) {
                return new BlogSyncResult("csdn", "accepted:" + request.postId(), originalUrl);
            }
            String articleId = data.path("article_id").asText("");
            String url = data.path("url").asText("");
            if (articleId.isBlank() || url.isBlank()) {
                throw new IllegalStateException("CSDN publish returned no article result");
            }
            return new BlogSyncResult("csdn", articleId, url);
        } catch (PlatformCsdnClient.CsdnSessionException exception) {
            credentials.markExpired(request.userId());
            throw exception;
        }
    }

    static String normalizeDescription(String summary) {
        if (summary == null) {
            return "";
        }
        String value = summary.strip();
        return value.length() > 200 ? value.substring(0, 200) : value;
    }

    static List<String> resolveCoverImages(String coverImage, String html) {
        if (coverImage != null && !coverImage.isBlank()) {
            return List.of(coverImage.strip());
        }
        Element image = Jsoup.parseBodyFragment(html == null ? "" : html)
                .selectFirst("img[src]");
        if (image == null || image.attr("src").isBlank()) {
            return List.of();
        }
        return List.of(image.attr("src").strip());
    }

    private String rewriteImages(String html, PlatformCsdnClient.Credential credential,
                                 String articleId, String originalUrl) {
        Document document = Jsoup.parseBodyFragment(html == null ? "" : html);
        if (originalUrl != null && !originalUrl.isBlank()) {
            Element link = new Element("p").appendElement("a")
                    .attr("href", originalUrl).attr("target", "_blank").text("原文链接");
            document.body().prependChild(link);
        }
        for (Element image : document.select("img[src]")) {
            String source = image.attr("src").strip();
            if (source.isBlank() || source.startsWith("data:")) {
                continue;
            }
            JsonNode response = client.uploadImage(credential, articleId, source);
            String hosted = extractImageUrl(response.path("data"));
            if (hosted.isBlank()) {
                throw new IllegalStateException("CSDN image upload returned empty url");
            }
            image.attr("src", hosted);
        }
        return document.body().html();
    }

    private String extractImageUrl(JsonNode data) {
        if (data == null || data.isMissingNode() || data.isNull()) {
            return "";
        }
        if (data.isTextual()) {
            return data.asText("");
        }
        for (String key : List.of("url", "imgUrl", "imageUrl", "image_url", "src", "link")) {
            String value = data.path(key).asText("");
            if (!value.isBlank()) {
                return value;
            }
        }
        return data.asText("");
    }

    private void validate(PublishRequest request) {
        if (request == null || request.userId() == null || request.userId() <= 0
                || request.postId() == null || request.postId() <= 0
                || request.title() == null || request.title().isBlank()
                || request.content() == null || request.content().isBlank()) {
            throw new IllegalArgumentException("CSDN publication payload is incomplete");
        }
    }

    public record PublishRequest(Long userId, Long postId, String title, String summary,
                                 String content, String category, String coverImage,
                                 List<String> tags) {
        public PublishRequest {
            tags = tags == null ? List.of() : new ArrayList<>(tags);
        }
    }
}
