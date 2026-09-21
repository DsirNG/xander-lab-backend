package com.xander.lab.automation.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.automation.host.ContentAutomationProperties;
import com.xander.lab.integration.api.BlogSyncResult;
import com.xander.lab.publishing.api.BlogPostVO;
import com.xander.lab.publishing.application.BlogPrivateReadService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
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
import java.util.List;
import java.util.Optional;

/**
 * Calls the platform-integration internal publication boundary.
 *
 * <p>The client carries owner and post ids explicitly, uses a bounded response, and keeps all
 * vendor credentials behind platform-integration. It never participates in the content database
 * transaction that records the local article.</p>
 */
@Component
public class PlatformBlogSyncClient {

    private static final String INTERNAL_TOKEN_HEADER = "X-DinQor-Internal-Token";

    private final RestClient restClient;
    private final ContentAutomationProperties properties;
    private final ObjectMapper objectMapper;
    private final Optional<BlogPrivateReadService> privateReadService;

    @Autowired
    public PlatformBlogSyncClient(RestClient.Builder restClientBuilder,
                                  ContentAutomationProperties properties,
                                  ObjectMapper objectMapper,
                                  Optional<BlogPrivateReadService> privateReadService) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(timeout(
                properties.getPlatformIntegrationConnectTimeoutMillis())));
        requestFactory.setReadTimeout(Duration.ofMillis(timeout(
                properties.getPlatformIntegrationReadTimeoutMillis())));
        this.restClient = restClientBuilder.requestFactory(requestFactory).build();
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.privateReadService = privateReadService;
    }

    /** Compatibility constructor for isolated client tests and the legacy bridge mode. */
    public PlatformBlogSyncClient(RestClient.Builder restClientBuilder,
                                  ContentAutomationProperties properties,
                                  ObjectMapper objectMapper) {
        this(restClientBuilder, properties, objectMapper, Optional.empty());
    }

    /** Publishes the local post to CSDN through platform-integration. */
    public BlogSyncResult syncCsdn(Long userId, Long postId) {
        return sync("csdn", userId, postId, new CsdnSyncRequest(userId, postId));
    }

    /** Publishes the local post to Juejin through platform-integration. */
    public BlogSyncResult syncJuejin(Long userId, Long postId) {
        BlogPostVO post = privateReadService.map(service -> service.findMine(userId, postId)).orElse(null);
        if (privateReadService.isPresent() && post == null) {
            throw new IllegalArgumentException("Blog post does not exist");
        }
        return sync("juejin", userId, postId,
                new JuejinSyncRequest(userId, postId, null, null, toPayload(post)));
    }

    private BlogSyncResult sync(String platform, Long userId, Long postId, Object request) {
        validateIds(userId, postId);
        String baseUrl = properties.getPlatformIntegrationBaseUrl();
        String internalToken = properties.getPlatformIntegrationInternalToken();
        if (baseUrl == null || baseUrl.isBlank() || internalToken == null || internalToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "站外同步服务尚未配置内网凭证");
        }
        try {
            ResponseEntity<byte[]> response = restClient.post()
                    .uri(URI.create(baseUrl.replaceAll("/+$", "")
                            + "/internal/platform-integration/blog-posts/" + postId
                            + "/sync/" + platform))
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(INTERNAL_TOKEN_HEADER, internalToken)
                    .body(request)
                    .exchange((clientRequest, clientResponse) -> new ResponseEntity<>(
                            copyBounded(clientResponse.getBody(),
                                    properties.getPlatformIntegrationMaxResponseBytes()),
                            clientResponse.getStatusCode()));
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "站外同步服务返回了不可用状态");
            }
            byte[] body = response.getBody();
            if (body == null || body.length == 0) {
                throw new IllegalStateException("站外同步服务没有返回结果");
            }
            BlogSyncResult result = objectMapper.readValue(body, BlogSyncResult.class);
            if (result == null || result.externalId() == null || result.externalId().isBlank()) {
                throw new IllegalStateException("站外同步服务返回了无效结果");
            }
            return result;
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "站外同步服务暂时无法连接", exception);
        }
    }

    private void validateIds(Long userId, Long postId) {
        if (userId == null || userId <= 0 || postId == null || postId <= 0) {
            throw new IllegalArgumentException("站外同步请求参数非法");
        }
    }

    private byte[] copyBounded(InputStream input, int configuredLimit) throws IOException {
        if (input == null) {
            return new byte[0];
        }
        int limit = Math.max(1, configuredLimit);
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (read > limit - total) {
                throw new IOException("站外同步响应超过允许大小");
            }
            output.write(buffer, 0, read);
            total += read;
        }
        return output.toByteArray();
    }

    private int timeout(int value) {
        return Math.max(1, value);
    }

    private record CsdnSyncRequest(Long userId, Long postId) {
    }

    private record JuejinSyncRequest(Long userId, Long postId, String juejinCategoryId,
                                     List<String> juejinTagIds, BlogPostPayload post) {
    }

    private record BlogPostPayload(Long id, String title, String summary, String content,
                                   String category, String categoryName, String coverImage,
                                   List<String> tags) {
    }

    private BlogPostPayload toPayload(BlogPostVO post) {
        if (post == null) {
            return null;
        }
        return new BlogPostPayload(post.getId(), post.getTitle(), post.getSummary(), post.getContent(),
                post.getCategory(), post.getCategoryName(), post.getCoverImage(), post.getTags());
    }
}
