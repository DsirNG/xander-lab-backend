package com.xander.lab.integration.host;

import com.xander.lab.common.Result;
import com.xander.lab.integration.api.BlogSyncResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Platform-owned public boundary for one-post vendor synchronization.
 *
 * <p>The public route now resolves the caller and owns the wire response here. During the
 * compatibility phase the actual vendor write is still executed through the token-protected
 * Core bridge, so there is one write path and no dual-owner publication state.</p>
 */
@RestController
@RequestMapping("/api/blog/posts")
@ConditionalOnProperty(prefix = "platform-integration", name = "internal-api-enabled",
        havingValue = "true")
public class PlatformBlogSyncController {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String UNAUTHORIZED_MESSAGE = "未登录或登录已过期";

    private final PlatformIntegrationInternalSyncController syncBridge;
    private final PlatformIntegrationProperties properties;

    public PlatformBlogSyncController(PlatformIntegrationInternalSyncController syncBridge,
                                      PlatformIntegrationProperties properties) {
        this.syncBridge = syncBridge;
        this.properties = properties;
    }

    @PostMapping("/{postId}/sync/csdn")
    public Result<BlogSyncResult> syncCsdn(
            @PathVariable Long postId,
            @RequestHeader(value = USER_ID_HEADER, required = false) String userIdHeader) {
        Long userId = parseUserId(userIdHeader);
        if (userId == null) {
            return Result.unauthorized(UNAUTHORIZED_MESSAGE);
        }
        return Result.success(syncBridge.syncCsdn(properties.getInternalToken(), postId,
                new PlatformIntegrationInternalSyncController.CsdnSyncRequest(userId, postId)));
    }

    @PostMapping("/{postId}/sync/juejin")
    public Result<BlogSyncResult> syncJuejin(
            @PathVariable Long postId,
            @RequestHeader(value = USER_ID_HEADER, required = false) String userIdHeader,
            @RequestBody(required = false) Map<String, Object> body) {
        Long userId = parseUserId(userIdHeader);
        if (userId == null) {
            return Result.unauthorized(UNAUTHORIZED_MESSAGE);
        }
        List<String> tagIds = body != null && body.get("juejinTagIds") instanceof List<?> tags
                ? tags.stream().map(String::valueOf).toList()
                : null;
        String categoryId = body == null || body.get("juejinCategoryId") == null
                ? null
                : String.valueOf(body.get("juejinCategoryId"));
        return Result.success(syncBridge.syncJuejin(properties.getInternalToken(), postId,
                new PlatformIntegrationInternalSyncController.JuejinSyncRequest(
                        userId, postId, categoryId, tagIds)));
    }

    private Long parseUserId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long userId = Long.parseLong(value.trim());
            return userId > 0 ? userId : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
