package com.xander.lab.gateway;

import com.xander.lab.core.api.SecurityStateSyncApi;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/** Gateway 对 core-host 安全状态只读同步 API 的 WebFlux 客户端。 */
public class GatewaySecurityStateClient {

    private static final String INTERNAL_TOKEN_HEADER = "X-DinQor-Internal-Token";

    private final WebClient webClient;
    private final GatewaySecurityStateProperties properties;
    private final Duration requestTimeout;

    public GatewaySecurityStateClient(GatewaySecurityStateProperties properties) {
        if (properties.getCoreHostBaseUrl() == null || properties.getCoreHostBaseUrl().isBlank()) {
            throw new IllegalStateException("Gateway 安全状态对账缺少 core-host 地址");
        }
        if (properties.getInternalToken() == null || properties.getInternalToken().isBlank()) {
            throw new IllegalStateException("Gateway 安全状态对账缺少内部令牌");
        }
        if (properties.getRequestTimeoutMs() < 1L) {
            throw new IllegalStateException("Gateway 安全状态对账请求超时必须大于 0");
        }
        this.properties = properties;
        this.requestTimeout = Duration.ofMillis(properties.getRequestTimeoutMs());
        this.webClient = WebClient.builder()
                .baseUrl(properties.getCoreHostBaseUrl())
                .build();
    }

    public SecurityStateSyncApi.Snapshot snapshot() {
        return get("/internal/security-state/snapshot", SecurityStateSyncApi.Snapshot.class);
    }

    public SecurityStateSyncApi.Changes changes(long cursor, int limit) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder.path("/internal/security-state/changes")
                        .queryParam("cursor", cursor)
                        .queryParam("limit", limit)
                        .build())
                .header(INTERNAL_TOKEN_HEADER, properties.getInternalToken())
                .retrieve()
                .bodyToMono(SecurityStateSyncApi.Changes.class)
                .block(requestTimeout);
    }

    private <T> T get(String path, Class<T> responseType) {
        return webClient.get()
                .uri(path)
                .header(INTERNAL_TOKEN_HEADER, properties.getInternalToken())
                .retrieve()
                .bodyToMono(responseType)
                .block(requestTimeout);
    }
}
