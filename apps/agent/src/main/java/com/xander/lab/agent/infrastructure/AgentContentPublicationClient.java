package com.xander.lab.agent.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.integration.api.BlogSyncResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;

/** Bounded Agent client for the Content-owned external publication ledger. */
@Component
@ConditionalOnProperty(prefix = "agent.content-client", name = "enabled", havingValue = "true")
public class AgentContentPublicationClient {

    private static final String TOKEN_HEADER = "X-DinQor-Internal-Token";

    private final RestClient client;
    private final AgentContentClientProperties properties;
    private final ObjectMapper objectMapper;

    public AgentContentPublicationClient(RestClient.Builder builder,
                                         AgentContentClientProperties properties,
                                         ObjectMapper objectMapper) {
        if (properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()
                || properties.getInternalToken() == null || properties.getInternalToken().isBlank()) {
            throw new IllegalStateException(
                    "agent.content-client.base-url/internal-token 在启用 content-client 时不能为空");
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeout(properties.getConnectTimeoutMillis())));
        factory.setReadTimeout(Duration.ofMillis(timeout(properties.getReadTimeoutMillis())));
        this.client = builder.requestFactory(factory).build();
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public BlogSyncResult sync(Long userId, Long postId, String platform) {
        return post(postId, platform, "/sync/" + platform,
                new SyncRequest(userId, postId), false);
    }

    public BlogSyncResult findSucceeded(Long userId, Long postId, String platform) {
        return post(postId, platform, "/find-succeeded/" + platform,
                new SyncRequest(userId, postId), true);
    }

    private BlogSyncResult post(Long postId, String platform, String action,
                                SyncRequest request, boolean allowEmpty) {
        if (postId == null || postId <= 0 || !("csdn".equals(platform) || "juejin".equals(platform))) {
            throw new IllegalArgumentException("站外同步请求参数非法");
        }
        try {
            ResponseEntity<byte[]> response = client.post()
                    .uri(URI.create(properties.getBaseUrl().replaceAll("/+$", "")
                            + "/internal/agent/publications/" + postId + action))
                    .header(TOKEN_HEADER, properties.getInternalToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .exchange((clientRequest, clientResponse) -> new ResponseEntity<>(
                            clientResponse.getStatusCode().is2xxSuccessful()
                                    ? copyBounded(clientResponse.getBody(), properties.getMaxResponseBytes())
                                    : new byte[0], clientResponse.getStatusCode()));
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new ResponseStatusException(BAD_GATEWAY, "内容服务暂时无法完成站外同步");
            }
            byte[] body = response.getBody();
            if (body == null || body.length == 0) {
                if (allowEmpty) return null;
                throw new ResponseStatusException(BAD_GATEWAY, "内容服务没有返回站外同步结果");
            }
            return objectMapper.readValue(body, BlogSyncResult.class);
        } catch (ResponseStatusException | IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(BAD_GATEWAY, "内容服务暂时无法连接", exception);
        }
    }

    private byte[] copyBounded(InputStream input, int configuredLimit) throws IOException {
        if (input == null) return new byte[0];
        int limit = Math.max(1, configuredLimit);
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (read > limit - total) throw new IOException("内容服务响应超过允许大小");
            output.write(buffer, 0, read);
            total += read;
        }
        return output.toByteArray();
    }

    private int timeout(int value) {
        return Math.max(1, value);
    }

    private record SyncRequest(Long userId, Long postId) {
    }
}
