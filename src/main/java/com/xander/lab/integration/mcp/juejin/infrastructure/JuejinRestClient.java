package com.xander.lab.integration.mcp.juejin.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;

/**
 * HTTP adapter for juejin.cn content_api. Sends the full browser Cookie header, the
 * x-secsdk-csrf-token header and the aid/uuid query pair captured with the account;
 * juejin rejects publish requests when the csrf token is missing or stale.
 */
@Component
@Slf4j
public class JuejinRestClient implements JuejinClient {
    private static final String CREATE_DRAFT_PATH = "/content_api/v1/article_draft/create";
    private static final String UPDATE_DRAFT_PATH = "/content_api/v1/article_draft/update";
    private static final String PUBLISH_PATH = "/content_api/v1/article/publish";

    private final JuejinPlatformProperties properties;
    private final RestClient client;

    public JuejinRestClient(JuejinPlatformProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeoutMillis());
        factory.setReadTimeout(properties.getReadTimeoutMillis());
        this.client = RestClient.builder().baseUrl(properties.getBaseUrl()).requestFactory(factory).build();
    }

    @Override
    public JsonNode createDraft(Credential credential, String title, String briefContent, String markContent,
                                String categoryId, List<String> tagIds, String coverImage) {
        return post(CREATE_DRAFT_PATH, credential, Map.ofEntries(
                Map.entry("title", title),
                Map.entry("brief_content", briefContent),
                Map.entry("mark_content", markContent),
                Map.entry("category_id", categoryId),
                Map.entry("tag_ids", tagIds == null ? List.of() : tagIds),
                Map.entry("link_url", ""),
                Map.entry("cover_image", coverImage == null ? "" : coverImage),
                Map.entry("edit_type", 10),
                Map.entry("html_content", "deprecated"),
                Map.entry("theme_ids", List.of()),
                Map.entry("pics", List.of())));
    }

    @Override
    public JsonNode updateDraft(Credential credential, String draftId, String title, String briefContent,
                                String markContent, String categoryId, List<String> tagIds, String coverImage) {
        return post(UPDATE_DRAFT_PATH, credential, Map.ofEntries(
                Map.entry("id", draftId),
                Map.entry("title", title),
                Map.entry("brief_content", briefContent),
                Map.entry("mark_content", markContent),
                Map.entry("category_id", categoryId),
                Map.entry("tag_ids", tagIds == null ? List.of() : tagIds),
                Map.entry("link_url", ""),
                Map.entry("cover_image", coverImage == null ? "" : coverImage),
                Map.entry("is_gfw", 0),
                Map.entry("is_english", 0),
                Map.entry("is_original", 1),
                Map.entry("edit_type", 10),
                Map.entry("html_content", "deprecated"),
                Map.entry("theme_ids", List.of()),
                Map.entry("pics", List.of())));
    }

    @Override
    public JsonNode publishDraft(Credential credential, String draftId) {
        return post(PUBLISH_PATH, credential, Map.of(
                "draft_id", draftId,
                "sync_to_org", false,
                "column_ids", List.of(),
                "theme_ids", List.of()));
    }

    @Override
    public JsonNode fetchUserProfile(Credential credential) {
        String url = "/user_api/v1/user/get?aid=" + url(credential.aid()) + "&uuid=" + url(credential.uuid()) + "&not_self=0";
        try {
            JsonNode response = client.get().uri(url)
                    .header(HttpHeaders.COOKIE, credential.cookie())
                    .header("origin", "https://juejin.cn")
                    .header("referer", "https://juejin.cn/")
                    .retrieve().body(JsonNode.class);
            if (response == null) throw new JuejinApiException(-1, "empty response");
            int errNo = response.path("err_no").asInt(-1);
            if (errNo != 0) {
                String errMsg = response.path("err_msg").asText("unknown error");
                if (isSessionInvalid(errNo, errMsg)) throw new JuejinSessionException(errMsg);
                throw new JuejinApiException(errNo, errMsg);
            }
            return response;
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 401 || ex.getStatusCode().value() == 403) {
                throw new JuejinSessionException("Juejin session invalid (HTTP " + ex.getStatusCode().value() + ")");
            }
            throw new JuejinApiException(-1, "HTTP " + ex.getStatusCode().value());
        }
    }

    private JsonNode post(String path, Credential credential, Map<String, Object> body) {
        String url = path + "?aid=" + url(credential.aid()) + "&uuid=" + url(credential.uuid());
        try {
            var request = client.post().uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.COOKIE, credential.cookie())
                    .header("origin", "https://juejin.cn")
                    .header("referer", "https://juejin.cn/");
            // CSRF 头随授权凭据一起保存；旧凭据缺省时先不带，避免空头被网关拒绝。
            if (credential.csrfToken() != null && !credential.csrfToken().isBlank()) {
                request = request.header("x-secsdk-csrf-token", credential.csrfToken());
            }
            JsonNode response = request.body(body).retrieve().body(JsonNode.class);
            if (response == null) throw new JuejinApiException(-1, "empty response");
            int errNo = response.path("err_no").asInt(-1);
            if (errNo != 0) {
                String errMsg = response.path("err_msg").asText("unknown error");
                if (isSessionInvalid(errNo, errMsg)) throw new JuejinSessionException(errMsg);
                throw new JuejinApiException(errNo, errMsg);
            }
            return response;
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 401 || ex.getStatusCode().value() == 403) {
                throw new JuejinSessionException("Juejin session invalid (HTTP " + ex.getStatusCode().value() + ")");
            }
            throw new JuejinApiException(-1, "HTTP " + ex.getStatusCode().value());
        }
    }

    /** 登录失效以 err_no=401 或「请登录」提示为准；其余错误不改变授权状态。 */
    private boolean isSessionInvalid(int errNo, String errMsg) {
        if (errNo == 401) return true;
        String message = errMsg == null ? "" : errMsg;
        return message.contains("请登录") || message.contains("登录失效") || message.contains("unauthorized");
    }

    private String url(String value) { return value == null ? "" : value; }
}