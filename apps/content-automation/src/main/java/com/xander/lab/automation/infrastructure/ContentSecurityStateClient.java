package com.xander.lab.automation.infrastructure;

import com.xander.lab.automation.host.ContentSecurityStateProperties;
import com.xander.lab.core.api.SecurityStateSyncApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** content-automation 对 core-host 安全状态只读同步 API 的 MVC 客户端。 */
@Component
@ConditionalOnProperty(prefix = "content-automation.security-state", name = "reconcile-enabled",
        havingValue = "true")
public class ContentSecurityStateClient {

    private static final String INTERNAL_TOKEN_HEADER = "X-DinQor-Internal-Token";

    private final RestClient restClient;
    private final ContentSecurityStateProperties properties;

    public ContentSecurityStateClient(RestClient.Builder restClientBuilder,
                                     ContentSecurityStateProperties properties) {
        if (properties.getCoreHostBaseUrl() == null || properties.getCoreHostBaseUrl().isBlank()) {
            throw new IllegalStateException("content-automation 安全状态对账缺少 core-host 地址");
        }
        if (properties.getInternalToken() == null || properties.getInternalToken().isBlank()) {
            throw new IllegalStateException("content-automation 安全状态对账缺少内部令牌");
        }
        if (properties.getRequestTimeoutMs() < 1L
                || properties.getRequestTimeoutMs() > Integer.MAX_VALUE) {
            throw new IllegalStateException("content-automation 安全状态对账请求超时必须在 1~2147483647ms 内");
        }
        this.properties = properties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        int timeout = (int) properties.getRequestTimeoutMs();
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        this.restClient = restClientBuilder
                .baseUrl(properties.getCoreHostBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    public SecurityStateSyncApi.Snapshot snapshot() {
        return restClient.get()
                .uri("/internal/security-state/snapshot")
                .header(INTERNAL_TOKEN_HEADER, properties.getInternalToken())
                .retrieve()
                .body(SecurityStateSyncApi.Snapshot.class);
    }

    public SecurityStateSyncApi.Changes changes(long cursor, int limit) {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/internal/security-state/changes")
                        .queryParam("cursor", cursor)
                        .queryParam("limit", limit)
                        .build())
                .header(INTERNAL_TOKEN_HEADER, properties.getInternalToken())
                .retrieve()
                .body(SecurityStateSyncApi.Changes.class);
    }
}
