package com.xander.lab.integration.host;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.Map;

/** HTTP adapter for the public Juejin category/tag catalog APIs. */
@Component
public class PlatformJuejinRestClient implements PlatformJuejinClient {

    private static final String CATEGORY_PATH = "/tag_api/v1/query_category_list";
    private static final String TAG_PATH = "/tag_api/v1/query_tag_list";

    private final PlatformJuejinProperties properties;
    private final RestClient client;

    public PlatformJuejinRestClient(PlatformJuejinProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeout(properties.getConnectTimeoutMillis())));
        factory.setReadTimeout(Duration.ofMillis(timeout(properties.getReadTimeoutMillis())));
        this.client = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(factory)
                .build();
    }

    @Override
    public JsonNode queryCategories(Credential credential) {
        return post(CATEGORY_PATH, credential, Map.of());
    }

    @Override
    public JsonNode queryTagList(Credential credential, String cursor, int limit) {
        return post(TAG_PATH, credential, Map.of(
                "cursor", cursor == null ? "0" : cursor,
                "key_word", "",
                "limit", limit,
                "sort_type", 1));
    }

    private JsonNode post(String path, Credential credential, Map<String, Object> body) {
        String aid = credential.aid() == null || credential.aid().isBlank()
                ? properties.getDefaultAid() : credential.aid();
        String uuid = credential.uuid() == null ? "" : credential.uuid();
        try {
            JsonNode response = client.post()
                    .uri(path + "?aid=" + aid + "&uuid=" + uuid)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.COOKIE, credential.cookie())
                    .header("origin", "https://juejin.cn")
                    .header("referer", "https://juejin.cn/")
                    .headers(headers -> {
                        if (credential.csrfToken() != null && !credential.csrfToken().isBlank()) {
                            headers.set("x-secsdk-csrf-token", credential.csrfToken());
                        }
                    })
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null) {
                throw new PlatformJuejinApiException("掘金目录接口返回空响应");
            }
            int errorNumber = response.path("err_no").asInt(0);
            if (errorNumber != 0) {
                throw new PlatformJuejinApiException(
                        response.path("err_msg").asText("掘金目录接口返回错误"));
            }
            return response;
        } catch (RestClientResponseException exception) {
            throw new PlatformJuejinApiException(
                    "掘金目录接口 HTTP " + exception.getStatusCode().value(), exception);
        }
    }

    private int timeout(int value) {
        return Math.max(1, value);
    }

    public static class PlatformJuejinApiException extends RuntimeException {
        public PlatformJuejinApiException(String message) {
            super(message);
        }

        public PlatformJuejinApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
