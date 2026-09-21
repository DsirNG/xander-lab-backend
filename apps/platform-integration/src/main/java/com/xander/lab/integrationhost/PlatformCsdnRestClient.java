package com.xander.lab.integration.host;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HTTP/signature adapter for the CSDN web console APIs. */
@Component
public class PlatformCsdnRestClient implements PlatformCsdnClient {

    private static final String SAVE_PATH = "/blog-console-api/v1/postedit/saveArticle";
    private static final String IMAGE_PATH = "/resource-api/v1/image/external/storage";
    private static final String ACCEPT = "application/json, text/plain, */*";
    private static final String CONTENT_TYPE = "application/json;";
    private static final String IMAGE_CONTENT_TYPE = "application/json;charset=UTF-8";

    private final PlatformCsdnProperties properties;
    private final RestClient client;

    public PlatformCsdnRestClient(PlatformCsdnProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeout(properties.getConnectTimeoutMillis())));
        factory.setReadTimeout(Duration.ofMillis(timeout(properties.getReadTimeoutMillis())));
        this.client = RestClient.builder().baseUrl(properties.getBaseUrl())
                .requestFactory(factory).build();
    }

    @Override
    public JsonNode saveArticle(Credential credential, Article article) {
        String nonce = UUID.randomUUID().toString();
        Map<String, Object> body = new HashMap<>();
        body.put("article_id", "");
        body.put("title", article.title());
        body.put("description", article.description());
        body.put("content", article.content());
        body.put("tags", String.join(",", article.tags() == null ? java.util.List.of() : article.tags()));
        body.put("categories", article.category() == null ? "" : article.category());
        body.put("type", "original");
        body.put("status", 0);
        body.put("read_type", "public");
        body.put("authorized_status", true);
        body.put("original_link", "");
        body.put("source", "pc_mdeditor");
        body.put("not_auto_saved", 0);
        body.put("cover_images", article.coverImages() == null ? java.util.List.of() : article.coverImages());
        body.put("cover_type", 0);
        body.put("vote_id", 0);
        body.put("resource_id", "");
        body.put("is_new", 1);
        body.put("sync_git_code", 0);
        try {
            JsonNode response = client.post().uri(SAVE_PATH)
                    .header("Accept", ACCEPT)
                    .header("Content-Type", CONTENT_TYPE)
                    .headers(headers -> signHeaders(headers, SAVE_PATH, CONTENT_TYPE,
                            UUID.randomUUID().toString()))
                    .header("Cookie", credential.cookie())
                    .body(body)
                    .retrieve().body(JsonNode.class);
            ensureSuccess(response, "CSDN article save");
            return response;
        } catch (RestClientResponseException exception) {
            throw translate(exception, "CSDN article save");
        }
    }

    @Override
    public JsonNode uploadImage(Credential credential, String articleId, String imageUrl) {
        String nonce = UUID.randomUUID().toString();
        String timestamp = String.valueOf(System.currentTimeMillis());
        Map<String, Object> body = Map.of(
                "art_id", articleId,
                "imgUrl", imageUrl,
                "type", "blog",
                "rtype", "article",
                "isCrawler", 0,
                "nocache", 2);
        try {
            JsonNode response = client.post().uri(IMAGE_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Accept", ACCEPT)
                    .header("Content-Type", IMAGE_CONTENT_TYPE)
                    .header("Origin", "https://mp.csdn.net")
                    .header("Referer", "https://mp.csdn.net/")
                    .header("Cookie", credential.cookie())
                    .headers(headers -> {
                        headers.set("x-ca-key", requiredApiKey());
                        headers.set("x-ca-nonce", nonce);
                        headers.set("x-ca-timestamp", timestamp);
                        headers.set("x-ca-signature-headers", "x-ca-key,x-ca-nonce,x-ca-timestamp");
                        headers.set("x-ca-signature", signImage(nonce, timestamp));
                    })
                    .body(body)
                    .retrieve().body(JsonNode.class);
            ensureSuccess(response, "CSDN image upload");
            return response;
        } catch (RestClientResponseException exception) {
            throw translate(exception, "CSDN image upload");
        }
    }

    private void ensureSuccess(JsonNode response, String operation) {
        if (response == null) {
            throw new CsdnApiException(operation + " returned empty response");
        }
        int code = response.path("code").asInt(0);
        if (code == 401) {
            throw new CsdnSessionException(operation + " session expired");
        }
        if (code != 200) {
            throw new CsdnApiException(operation + " failed: "
                    + response.path("msg").asText("unknown error"));
        }
    }

    private RuntimeException translate(RestClientResponseException exception, String operation) {
        int status = exception.getStatusCode().value();
        if (status == 401 || status == 403) {
            return new CsdnSessionException(operation + " HTTP " + status, exception);
        }
        return new CsdnApiException(operation + " HTTP " + status, exception);
    }

    private void signHeaders(org.springframework.http.HttpHeaders headers, String path,
                             String contentType, String nonce) {
        headers.set("x-ca-key", requiredApiKey());
        headers.set("x-ca-nonce", nonce);
        headers.set("x-ca-signature-headers", "x-ca-key,x-ca-nonce");
        headers.set("x-ca-signature", signSave(path, contentType, nonce));
    }

    private String signSave(String path, String contentType, String nonce) {
        String canonical = "POST\n" + ACCEPT + "\n\n" + contentType + "\n\n"
                + "x-ca-key:" + requiredApiKey() + "\n"
                + "x-ca-nonce:" + nonce + "\n" + path;
        return hmac(canonical);
    }

    private String signImage(String nonce, String timestamp) {
        String canonical = "POST\n" + ACCEPT + "\n\n" + IMAGE_CONTENT_TYPE + "\n\n"
                + "x-ca-key:" + requiredApiKey() + "\n"
                + "x-ca-nonce:" + nonce + "\n"
                + "x-ca-timestamp:" + timestamp + "\n" + IMAGE_PATH;
        return hmac(canonical);
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getWebConsoleSecret()
                    .getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(
                    mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new CsdnApiException("Unable to sign CSDN request", exception);
        }
    }

    private String requiredApiKey() {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new IllegalStateException("platform-integration.csdn.api-key is required");
        }
        return properties.getApiKey();
    }

    private int timeout(int value) {
        return Math.max(1, value);
    }
}
