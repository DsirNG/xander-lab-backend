package com.xander.lab.integration.internal.api;

import com.xander.lab.integration.api.BlogSyncResult;
import com.xander.lab.integration.internal.application.IntegrationInternalApiProperties;
import com.xander.lab.integration.mcp.csdn.application.CsdnBlogSyncService;
import com.xander.lab.integration.mcp.juejin.application.JuejinBlogSyncService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Token-protected sync boundary used while platform-integration drains the legacy Core
 * implementation.
 *
 * <p>The user id is explicit because a service-to-service request has no end-user thread-local.
 * The path id and body id are checked together so a proxy cannot accidentally publish a
 * different resource than the caller intended.</p>
 */
@RestController
@RequestMapping("/internal/integration/blog-posts")
@ConditionalOnProperty(prefix = "integration.internal", name = "api-enabled", havingValue = "true")
public class InternalBlogSyncController {

    private static final String INTERNAL_TOKEN_HEADER = "X-DinQor-Internal-Token";

    private final CsdnBlogSyncService csdnBlogSyncService;
    private final JuejinBlogSyncService juejinBlogSyncService;
    private final IntegrationInternalApiProperties properties;

    public InternalBlogSyncController(CsdnBlogSyncService csdnBlogSyncService,
                                      JuejinBlogSyncService juejinBlogSyncService,
                                      IntegrationInternalApiProperties properties) {
        this.csdnBlogSyncService = csdnBlogSyncService;
        this.juejinBlogSyncService = juejinBlogSyncService;
        this.properties = properties;
        if (properties.getInternalToken() == null || properties.getInternalToken().isBlank()) {
            throw new IllegalStateException(
                    "security.state-sync.internal-token 在启用站外同步内网 API 时不能为空");
        }
    }

    /** Executes one idempotent CSDN publication for the explicitly owned post. */
    @PostMapping("/{postId}/sync/csdn")
    public BlogSyncResult syncCsdn(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @PathVariable Long postId,
            @RequestBody CsdnSyncRequest request) {
        authorize(token);
        validatePost(postId, request.userId(), request.postId());
        return csdnBlogSyncService.sync(request.userId(), postId);
    }

    /** Executes one idempotent Juejin publication for the explicitly owned post. */
    @PostMapping("/{postId}/sync/juejin")
    public BlogSyncResult syncJuejin(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @PathVariable Long postId,
            @RequestBody JuejinSyncRequest request) {
        authorize(token);
        validatePost(postId, request.userId(), request.postId());
        return juejinBlogSyncService.sync(request.userId(), postId,
                request.juejinCategoryId(), request.juejinTagIds());
    }

    /** Reads a prior CSDN success without contacting the external platform. */
    @PostMapping("/{postId}/find-succeeded/csdn")
    public ResponseEntity<BlogSyncResult> findSucceededCsdn(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @PathVariable Long postId,
            @RequestBody CsdnSyncRequest request) {
        authorize(token);
        validatePost(postId, request.userId(), request.postId());
        BlogSyncResult result = csdnBlogSyncService.findSucceeded(request.userId(), postId);
        return result == null ? ResponseEntity.ok().build() : ResponseEntity.ok(result);
    }

    /** Reads a prior Juejin success without contacting the external platform. */
    @PostMapping("/{postId}/find-succeeded/juejin")
    public ResponseEntity<BlogSyncResult> findSucceededJuejin(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @PathVariable Long postId,
            @RequestBody CsdnSyncRequest request) {
        authorize(token);
        validatePost(postId, request.userId(), request.postId());
        BlogSyncResult result = juejinBlogSyncService.findSucceeded(request.userId(), postId);
        return result == null ? ResponseEntity.ok().build() : ResponseEntity.ok(result);
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

    /** CSDN internal request: explicit owner and idempotency resource. */
    public record CsdnSyncRequest(Long userId, Long postId) {
    }

    /** Juejin internal request: explicit owner, resource, and optional platform metadata. */
    public record JuejinSyncRequest(Long userId, Long postId, String juejinCategoryId,
                                    List<String> juejinTagIds) {
    }
}
