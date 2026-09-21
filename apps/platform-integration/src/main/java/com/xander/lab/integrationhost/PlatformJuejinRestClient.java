package com.xander.lab.integration.host;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** HTTP adapter for the public Juejin category/tag catalog APIs. */
@Component
public class PlatformJuejinRestClient implements PlatformJuejinClient {

    private static final String CATEGORY_PATH = "/tag_api/v1/query_category_list";
    private static final String TAG_PATH = "/tag_api/v1/query_tag_list";
    private static final String PROFILE_PATH = "/user_api/v1/user/get";
    private static final String CREATE_DRAFT_PATH = "/content_api/v1/article_draft/create";
    private static final String PUBLISH_PATH = "/content_api/v1/article/publish";

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

    @Override
    public JsonNode fetchUserProfile(Credential credential) {
        try {
            JsonNode response = client.get()
                    .uri(PROFILE_PATH + "?aid=" + encode(credential.aid())
                            + "&uuid=" + encode(credential.uuid()) + "&not_self=0")
                    .header(HttpHeaders.COOKIE, credential.cookie())
                    .header("origin", "https://juejin.cn")
                    .header("referer", "https://juejin.cn/")
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null) {
                throw new JuejinApiException("掘金个人资料接口返回空响应");
            }
            int errorNumber = response.path("err_no").asInt(0);
            if (errorNumber != 0) {
                String message = response.path("err_msg").asText("掘金个人资料接口返回错误");
                if (isSessionInvalid(errorNumber, message)) {
                    throw new JuejinSessionException(message);
                }
                throw new JuejinApiException(errorNumber, message);
            }
            return response;
        } catch (RestClientResponseException exception) {
            if (isSessionStatus(exception)) {
                throw new JuejinSessionException(
                        "掘金个人资料接口 HTTP " + exception.getStatusCode().value(), exception);
            }
            throw new JuejinApiException(-1,
                    "掘金个人资料接口 HTTP " + exception.getStatusCode().value());
        }
    }

    @Override
    public JsonNode createDraft(Credential credential, String title, String briefContent,
                                String markContent, String categoryId, java.util.List<String> tagIds,
                                String coverImage) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", title);
        body.put("brief_content", briefContent);
        body.put("mark_content", markContent);
        body.put("category_id", categoryId);
        body.put("tag_ids", tagIds == null ? java.util.List.of() : tagIds);
        body.put("link_url", "");
        body.put("cover_image", coverImage == null ? "" : coverImage);
        body.put("edit_type", 10);
        body.put("html_content", "deprecated");
        body.put("theme_ids", java.util.List.of());
        body.put("pics", java.util.List.of());
        return post(CREATE_DRAFT_PATH, credential, body);
    }

    @Override
    public JsonNode publishDraft(Credential credential, String draftId) {
        return post(PUBLISH_PATH, credential, Map.of(
                "draft_id", draftId,
                "sync_to_org", false,
                "column_ids", java.util.List.of(),
                "theme_ids", java.util.List.of()));
    }

    @Override
    public JsonNode searchTags(String keyword, int limit) {
        try {
            JsonNode response = client.get()
                    .uri(builder -> builder.path(TAG_PATH)
                            .queryParam("key_word", keyword == null ? "" : keyword)
                            .queryParam("cursor", "0")
                            .queryParam("limit", limit)
                            .build())
                    .header("origin", "https://juejin.cn")
                    .header("referer", "https://juejin.cn/")
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null || response.path("err_no").asInt(0) != 0) {
                throw new JuejinApiException("掘金标签搜索返回错误");
            }
            return response;
        } catch (RestClientResponseException exception) {
            throw new JuejinApiException(-1,
                    "掘金标签搜索接口 HTTP " + exception.getStatusCode().value());
        }
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
                throw new JuejinApiException("掘金目录接口返回空响应");
            }
            int errorNumber = response.path("err_no").asInt(0);
            if (errorNumber != 0) {
                String message = response.path("err_msg").asText("掘金目录接口返回错误");
                if (isSessionInvalid(errorNumber, message)) {
                    throw new JuejinSessionException(message);
                }
                throw new JuejinApiException(errorNumber, message);
            }
            return response;
        } catch (RestClientResponseException exception) {
            if (isSessionStatus(exception)) {
                throw new JuejinSessionException(
                        "掘金目录接口 HTTP " + exception.getStatusCode().value(), exception);
            }
            throw new JuejinApiException(-1,
                    "掘金目录接口 HTTP " + exception.getStatusCode().value());
        }
    }

    private int timeout(int value) {
        return Math.max(1, value);
    }

    private String encode(String value) {
        return value == null ? "" : URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private boolean isSessionInvalid(int errorNumber, String message) {
        if (errorNumber == 401) {
            return true;
        }
        String value = message == null ? "" : message;
        return value.contains("请登录") || value.contains("登录失效")
                || value.toLowerCase().contains("unauthorized");
    }

    private boolean isSessionStatus(RestClientResponseException exception) {
        int status = exception.getStatusCode().value();
        return status == 401 || status == 403;
    }
}
