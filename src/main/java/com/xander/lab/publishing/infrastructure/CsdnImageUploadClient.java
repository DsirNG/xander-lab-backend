package com.xander.lab.publishing.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/** Default CSDN image storage adapter. */
@Component
class CsdnImageUploadClient implements CsdnImageStorageClient {
    private static final String PATH = "/resource-api/v1/image/external/storage";
    private final RestClient client;

    CsdnImageUploadClient(CsdnProperties properties) {
        this.client = RestClient.builder().baseUrl(properties.getBaseUrl()).build();
    }

    @Override
    public String upload(String cookie, String apiKey, String nonce, String signature, String timestamp, String imageUrl, String articleId) {
        JsonNode response = client.post().uri(PATH)
                .header("Accept", "application/json, text/plain, */*")
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("Origin", "https://mp.csdn.net")
                .header("Referer", "https://mp.csdn.net/")
                .header("Cookie", cookie)
                .header("x-ca-key", apiKey)
                .header("x-ca-nonce", nonce)
                .header("x-ca-signature", signature)
                .header("x-ca-signature-headers", "x-ca-key,x-ca-nonce,x-ca-timestamp")
                .header("x-ca-timestamp", timestamp)
                .body(Map.of(
                        "art_id", articleId,
                        "imgUrl", imageUrl,
                        "type", "blog",
                        "rtype", "article",
                        "isCrawler", 0,
                        "nocache", 2))
                .retrieve().body(JsonNode.class);
        if (response == null || response.path("code").asInt(0) != 200) {
            throw new IllegalStateException("CSDN image upload failed: " + (response == null ? "empty response" : response.path("msg").asText("unknown error")));
        }
        String data = extractUrl(response.path("data"));
        if (data.isBlank()) {
            throw new IllegalStateException("CSDN image upload returned empty url");
        }
        return data;
    }

    static String extractUrl(JsonNode data) {
        if (data == null || data.isNull() || data.isMissingNode()) {
            return "";
        }
        if (data.isTextual()) {
            return data.asText("");
        }
        if (data.isObject()) {
            String[] keys = {"url", "imgUrl", "imageUrl", "image_url", "src", "link"};
            for (String key : keys) {
                String value = data.path(key).asText("");
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        return data.asText("");
    }
}
