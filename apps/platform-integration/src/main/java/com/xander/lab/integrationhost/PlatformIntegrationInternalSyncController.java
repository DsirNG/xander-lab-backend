package com.xander.lab.integration.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.integration.api.BlogSyncResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;

/**
 * Authenticated bridge from content-automation to the Core integration compatibility boundary.
 *
 * <p>This bridge owns no publication state. It validates the caller token, replaces it with the
 * Core token, bounds the response, and translates transport failures to a generic 502.</p>
 */
@RestController
@RequestMapping("/internal/platform-integration/blog-posts")
@ConditionalOnProperty(prefix = "platform-integration", name = "internal-api-enabled",
        havingValue = "true")
public class PlatformIntegrationInternalSyncController {

    private static final String INTERNAL_TOKEN_HEADER = "X-DinQor-Internal-Token";

    private final RestClient restClient;
    private final PlatformIntegrationProperties properties;
    private final ObjectMapper objectMapper;

    public PlatformIntegrationInternalSyncController(RestClient.Builder restClientBuilder,
                                                      PlatformIntegrationProperties properties,
                                                      ObjectMapper objectMapper) {
        if (properties.getInternalToken() == null || properties.getInternalToken().isBlank()) {
            throw new IllegalStateException(
                    "platform-integration.internal-token 在启用内网站外同步 API 时不能为空");
        }
        if (properties.getCoreInternalToken() == null || properties.getCoreInternalToken().isBlank()) {
            throw new IllegalStateException(
                    "platform-integration.core-internal-token 在启用内网站外同步 API 时不能为空");
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(timeout(properties.getConnectTimeoutMillis())));
        requestFactory.setReadTimeout(Duration.ofMillis(timeout(properties.getReadTimeoutMillis())));
        this.restClient = restClientBuilder.requestFactory(requestFactory).build();
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** Forwards one CSDN sync while replacing the caller token with the Core token. */
    @PostMapping("/{postId}/sync/csdn")
    public BlogSyncResult syncCsdn(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @PathVariable Long postId,
            @RequestBody CsdnSyncRequest request) {
        authorize(token);
        validatePost(postId, request.userId(), request.postId());
        return forward("/internal/integration/blog-posts/" + postId + "/sync/csdn",
                new CsdnSyncRequest(request.userId(), postId));
    }

    /** Forwards one Juejin sync while preserving optional category and tag ids. */
    @PostMapping("/{postId}/sync/juejin")
    public BlogSyncResult syncJuejin(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @PathVariable Long postId,
            @RequestBody JuejinSyncRequest request) {
        authorize(token);
        validatePost(postId, request.userId(), request.postId());
        return forward("/internal/integration/blog-posts/" + postId + "/sync/juejin",
                new JuejinSyncRequest(request.userId(), postId,
                        request.juejinCategoryId(), request.juejinTagIds()));
    }

    private BlogSyncResult forward(String path, Object request) {
        try {
            ResponseEntity<byte[]> response = restClient.post()
                    .uri(targetUri(path))
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(INTERNAL_TOKEN_HEADER, properties.getCoreInternalToken())
                    .body(request)
                    .exchange((clientRequest, clientResponse) -> new ResponseEntity<>(
                            copyBounded(clientResponse.getBody(), properties.getMaxResponseBytes()),
                            clientResponse.getStatusCode()));
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new ResponseStatusException(BAD_GATEWAY,
                        "平台集成服务暂时无法完成站外同步");
            }
            byte[] body = response.getBody();
            if (body == null || body.length == 0) {
                throw new ResponseStatusException(BAD_GATEWAY,
                        "平台集成服务返回了空的站外同步结果");
            }
            return objectMapper.readValue(body, BlogSyncResult.class);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(BAD_GATEWAY,
                    "平台集成服务暂时无法连接 Core 兼容边界", exception);
        }
    }

    private URI targetUri(String path) {
        String baseUrl = properties.getCoreBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("platform-integration.core-base-url 不能为空");
        }
        return URI.create(baseUrl.replaceAll("/+$", "") + path);
    }

    private void authorize(String token) {
        if (token == null || !MessageDigest.isEqual(
                properties.getInternalToken().getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not Found");
        }
    }

    private void validatePost(Long pathPostId, Long userId, Long bodyPostId) {
        if (userId == null || userId <= 0 || pathPostId == null || pathPostId <= 0
                || bodyPostId == null || !pathPostId.equals(bodyPostId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "站外同步请求参数非法");
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

    public record CsdnSyncRequest(Long userId, Long postId) {
    }

    public record JuejinSyncRequest(Long userId, Long postId, String juejinCategoryId,
                                    List<String> juejinTagIds) {
    }
}
