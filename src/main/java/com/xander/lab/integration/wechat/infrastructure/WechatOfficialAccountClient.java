package com.xander.lab.integration.wechat.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.xander.lab.integration.wechat.WechatOfficialAccountProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.UrlResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.MalformedURLException;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Thin adapter around the official-account token, image, and draft APIs. */
@Slf4j
@Component
public class WechatOfficialAccountClient {
    private final RestClient client;

    public WechatOfficialAccountClient(WechatOfficialAccountProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMillis()));
        requestFactory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMillis()));
        this.client = RestClient.builder()
                .baseUrl(properties.getApiBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    /** Gets an app-level access token; the caller owns persistence and refresh policy. */
    public AccessToken getAccessToken(String appId, String appSecret) {
        try {
            JsonNode response = client.get()
                    .uri(uriBuilder -> uriBuilder.path("/cgi-bin/token")
                            .queryParam("grant_type", "client_credential")
                            .queryParam("appid", appId)
                            .queryParam("secret", appSecret)
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
            ensureWechatSuccess(response, "获取微信公众号 access_token");
            String token = response.path("access_token").asText("");
            int expiresIn = response.path("expires_in").asInt(7200);
            if (token.isBlank()) throw new IllegalStateException("微信公众号未返回 access_token");
            return new AccessToken(token, Math.max(300, expiresIn));
        } catch (RestClientResponseException e) {
            throw remoteFailure("获取微信公众号 access_token", e);
        } catch (RestClientException e) {
            throw new IllegalStateException("获取微信公众号 access_token 失败，请检查网络或微信接口可用性", e);
        }
    }

    /** Uploads a public cover URL as a permanent image and returns its media_id. */
    public String uploadPermanentImage(String accessToken, String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            throw new IllegalArgumentException("微信公众号同步需要封面图");
        }
        try {
            URI imageUri = URI.create(imageUrl.trim());
            String scheme = imageUri.getScheme();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("封面图地址必须使用 http/https");
            }
            UrlResource resource = new UrlResource(imageUri.toURL());
            MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
            parts.add("media", resource);
            try {
                JsonNode response = client.post()
                        .uri(uriBuilder -> uriBuilder.path("/cgi-bin/material/add_material")
                                .queryParam("access_token", accessToken)
                                .queryParam("type", "image")
                                .build())
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .body(parts)
                        .retrieve()
                        .body(JsonNode.class);
                ensureWechatSuccess(response, "上传微信公众号封面");
                String mediaId = response.path("media_id").asText("");
                if (mediaId.isBlank()) throw new IllegalStateException("微信公众号未返回封面 media_id");
                return mediaId;
            } catch (RestClientResponseException e) {
                throw remoteFailure("上传微信公众号封面", e);
            } catch (RestClientException e) {
                throw new IllegalStateException("上传微信公众号封面失败，请检查图片地址和网络", e);
            }
        } catch (MalformedURLException | IllegalArgumentException e) {
            throw new IllegalArgumentException("封面图地址无效，必须是可访问的 http/https 地址", e);
        }
    }

    /** Creates one news article draft and returns the draft media_id. */
    public String createDraft(String accessToken, String title, String author, String digest,
                              String htmlContent, String contentSourceUrl, String thumbMediaId) {
        Map<String, Object> article = new LinkedHashMap<>();
        article.put("title", limit(title, 64));
        article.put("author", limit(author, 16));
        article.put("digest", limit(digest, 120));
        article.put("content", htmlContent == null ? "" : htmlContent);
        article.put("content_source_url", contentSourceUrl == null ? "" : contentSourceUrl);
        article.put("thumb_media_id", thumbMediaId);
        article.put("need_open_comment", 1);
        article.put("only_fans_can_comment", 0);

        try {
            JsonNode response = client.post()
                    .uri(uriBuilder -> uriBuilder.path("/cgi-bin/draft/add")
                            .queryParam("access_token", accessToken)
                            .build())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("articles", java.util.List.of(article)))
                    .retrieve()
                    .body(JsonNode.class);
            ensureWechatSuccess(response, "创建微信公众号草稿");
            String mediaId = response.path("media_id").asText("");
            if (mediaId.isBlank()) throw new IllegalStateException("微信公众号未返回草稿 media_id");
            return mediaId;
        } catch (RestClientResponseException e) {
            throw remoteFailure("创建微信公众号草稿", e);
        } catch (RestClientException e) {
            throw new IllegalStateException("创建微信公众号草稿失败，请检查网络或文章内容", e);
        }
    }

    static void ensureWechatSuccess(JsonNode response, String operation) {
        if (response == null) throw new IllegalStateException(operation + "失败：微信返回为空");
        int errorCode = response.path("errcode").asInt(0);
        if (errorCode != 0) {
            String message = response.path("errmsg").asText("unknown error");
            throw new IllegalStateException(operation + "失败（" + errorCode + "）：" + message);
        }
    }

    private static String limit(String value, int max) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }

    private static IllegalStateException remoteFailure(String operation, RestClientResponseException exception) {
        return new IllegalStateException(operation + "失败，微信接口 HTTP 状态："
                + exception.getStatusCode().value(), exception);
    }

    public record AccessToken(String value, int expiresInSeconds) {
    }
}
