package com.xander.lab.agent.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.content.api.ContentPost;
import com.xander.lab.content.api.ContentPostPort;
import org.springframework.beans.factory.annotation.Autowired;
import com.xander.lab.integration.api.BlogSyncResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;

/**
 * Bounded HTTP client for Agent's CSDN/Juejin publication ports.
 *
 * <p>The client is opt-in. When enabled it replaces the fail-closed contract fallbacks and keeps
 * the caller's explicit owner id in every request; it never relies on a user thread-local.</p>
 */
@Component
@ConditionalOnProperty(prefix = "agent.platform-client", name = "enabled", havingValue = "true")
public class AgentPlatformBlogSyncClient {

    private static final String INTERNAL_TOKEN_HEADER = "X-DinQor-Internal-Token";

    private final RestClient restClient;
    private final AgentPlatformClientProperties properties;
    private final ObjectMapper objectMapper;
    private final Optional<ContentPostPort> contentPostPort;

    @Autowired
    public AgentPlatformBlogSyncClient(RestClient.Builder builder,
                                       AgentPlatformClientProperties properties,
                                       ObjectMapper objectMapper,
                                       Optional<ContentPostPort> contentPostPort) {
        if (properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()) {
            throw new IllegalStateException("agent.platform-client.base-url 在启用 platform-client 时不能为空");
        }
        if (properties.getInternalToken() == null || properties.getInternalToken().isBlank()) {
            throw new IllegalStateException("agent.platform-client.internal-token 在启用 platform-client 时不能为空");
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(timeout(properties.getConnectTimeoutMillis())));
        requestFactory.setReadTimeout(Duration.ofMillis(timeout(properties.getReadTimeoutMillis())));
        this.restClient = builder.requestFactory(requestFactory).build();
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.contentPostPort = contentPostPort;
    }

    /** Compatibility constructor for isolated client tests and legacy Agent deployments. */
    public AgentPlatformBlogSyncClient(RestClient.Builder builder,
                                       AgentPlatformClientProperties properties,
                                       ObjectMapper objectMapper) {
        this(builder, properties, objectMapper, Optional.empty());
    }

    public BlogSyncResult syncCsdn(Long userId, Long postId) {
        return post("/internal/platform-integration/blog-posts/" + postId + "/sync/csdn",
                new CsdnRequest(userId, postId), false);
    }

    public BlogSyncResult syncJuejin(Long userId, Long postId,
                                     String juejinCategoryId, List<String> juejinTagIds) {
        ContentPost post = contentPostPort.map(port -> port.findOwned(userId, postId)).orElse(null);
        if (contentPostPort.isPresent() && post == null) {
            throw new IllegalArgumentException("Blog post does not exist");
        }
        return post("/internal/platform-integration/blog-posts/" + postId + "/sync/juejin",
                new JuejinRequest(userId, postId, juejinCategoryId, juejinTagIds,
                        toPayload(post)), false);
    }

    public BlogSyncResult findSucceededCsdn(Long userId, Long postId) {
        return post("/internal/platform-integration/blog-posts/" + postId + "/find-succeeded/csdn",
                new CsdnRequest(userId, postId), true);
    }

    public BlogSyncResult findSucceededJuejin(Long userId, Long postId) {
        return post("/internal/platform-integration/blog-posts/" + postId + "/find-succeeded/juejin",
                new CsdnRequest(userId, postId), true);
    }

    private BlogSyncResult post(String path, Object request, boolean allowEmpty) {
        try {
            ResponseEntity<byte[]> response = restClient.post()
                    .uri(targetUri(path))
                    .header(INTERNAL_TOKEN_HEADER, properties.getInternalToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .exchange((clientRequest, clientResponse) -> {
                        byte[] body = clientResponse.getStatusCode().is2xxSuccessful()
                                ? copyBounded(clientResponse.getBody(), properties.getMaxResponseBytes())
                                : new byte[0];
                        return new ResponseEntity<>(body, clientResponse.getStatusCode());
                    });
            int status = response.getStatusCode().value();
            if (status == 400) {
                throw new IllegalArgumentException("站外同步请求参数非法");
            }
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new ResponseStatusException(BAD_GATEWAY, "平台集成服务暂时无法完成站外同步");
            }
            byte[] body = response.getBody();
            if (body == null || body.length == 0) {
                if (allowEmpty) return null;
                throw new ResponseStatusException(BAD_GATEWAY, "平台集成服务返回了空的站外同步结果");
            }
            return objectMapper.readValue(body, BlogSyncResult.class);
        } catch (IllegalArgumentException | ResponseStatusException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 400) {
                throw new IllegalArgumentException("站外同步请求参数非法", exception);
            }
            throw new ResponseStatusException(BAD_GATEWAY, "平台集成服务暂时无法完成站外同步", exception);
        } catch (Exception exception) {
            if (hasBadRequestCause(exception)) {
                throw new IllegalArgumentException("站外同步请求参数非法", exception);
            }
            throw new ResponseStatusException(BAD_GATEWAY, "平台集成服务暂时无法连接", exception);
        }
    }

    private boolean hasBadRequestCause(Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current instanceof RestClientResponseException response
                    && response.getStatusCode().value() == 400) {
                return true;
            }
        }
        return false;
    }

    private URI targetUri(String path) {
        return URI.create(properties.getBaseUrl().replaceAll("/+$", "") + path);
    }

    private byte[] copyBounded(InputStream input, int configuredLimit) throws IOException {
        if (input == null) return new byte[0];
        int limit = Math.max(1, configuredLimit);
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (read > limit - total) throw new IOException("站外同步响应超过允许大小");
            output.write(buffer, 0, read);
            total += read;
        }
        return output.toByteArray();
    }

    private int timeout(int value) {
        return Math.max(1, value);
    }

    private record CsdnRequest(Long userId, Long postId) {
    }

    private record JuejinRequest(Long userId, Long postId, String juejinCategoryId,
                                 List<String> juejinTagIds, BlogPostPayload post) {
    }

    private record BlogPostPayload(Long id, String title, String summary, String content,
                                   String category, String categoryName, String coverImage,
                                   List<String> tags) {
    }

    private BlogPostPayload toPayload(ContentPost post) {
        if (post == null) {
            return null;
        }
        return new BlogPostPayload(post.id(), post.title(), post.summary(), post.content(),
                post.category(), post.categoryName(), post.coverImage(), post.tags());
    }
}
