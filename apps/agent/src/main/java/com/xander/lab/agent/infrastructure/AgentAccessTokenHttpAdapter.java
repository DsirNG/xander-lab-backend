package com.xander.lab.agent.infrastructure;

import com.xander.lab.core.api.AccessTokenLookupPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 通过 core-host 的内网 API 实现访问令牌查询。
 *
 * <p>访问令牌只存在于请求体中，既不拼进 URL，也不写入日志。内网调用失败按契约返回
 * {@code null}，让握手继续 fail-closed。</p>
 */
@Component
@ConditionalOnProperty(prefix = "agent.core-client", name = "enabled", havingValue = "true")
public class AgentAccessTokenHttpAdapter implements AccessTokenLookupPort {
    private static final Logger log = LoggerFactory.getLogger(AgentAccessTokenHttpAdapter.class);
    private static final String INTERNAL_TOKEN_HEADER = "X-DinQor-Internal-Token";

    private final RestClient restClient;
    private final String internalToken;

    public AgentAccessTokenHttpAdapter(RestClient.Builder builder,
                                       AgentCoreClientProperties properties) {
        this(builder.baseUrl(normalizeBaseUrl(properties.getBaseUrl())).build(), properties);
    }

    AgentAccessTokenHttpAdapter(RestClient restClient, AgentCoreClientProperties properties) {
        if (properties.getInternalToken() == null || properties.getInternalToken().isBlank()) {
            throw new IllegalStateException(
                    "agent.core-client.internal-token 在启用 core-client 时不能为空");
        }
        this.restClient = restClient;
        this.internalToken = properties.getInternalToken();
    }

    @Override
    public Long resolveUserId(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return null;
        }
        try {
            ResolveResponse response = restClient.post()
                    .uri("/internal/agent/auth/resolve")
                    .header(INTERNAL_TOKEN_HEADER, internalToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ResolveRequest(accessToken))
                    .retrieve()
                    .body(ResolveResponse.class);
            return response == null ? null : response.userId();
        } catch (RestClientException exception) {
            log.warn("core-host access-token lookup unavailable; rejecting stream handshake");
            return null;
        }
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("agent.core-client.base-url 在启用 core-client 时不能为空");
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private record ResolveRequest(String accessToken) {
    }

    private record ResolveResponse(Long userId) {
    }
}
