package com.xander.lab.integration.host;

import com.xander.lab.common.Result;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Platform-owned public boundary for manually refreshing Juejin's shared catalog. */
@RestController
@RequestMapping("/api/publishing/juejin/catalog")
@ConditionalOnProperty(prefix = "platform-integration", name = "internal-api-enabled",
        havingValue = "true")
public class PlatformJuejinCatalogController {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String UNAUTHORIZED_MESSAGE = "未登录或登录已过期";

    private final PlatformIntegrationInternalCatalogController catalogBridge;
    private final PlatformIntegrationProperties properties;

    public PlatformJuejinCatalogController(
            PlatformIntegrationInternalCatalogController catalogBridge,
            PlatformIntegrationProperties properties) {
        this.catalogBridge = catalogBridge;
        this.properties = properties;
    }

    @PostMapping("/sync")
    public Result<Void> sync(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userIdHeader) {
        Long userId = parseUserId(userIdHeader);
        if (userId == null) {
            return Result.unauthorized(UNAUTHORIZED_MESSAGE);
        }
        catalogBridge.sync(properties.getInternalToken(), userId);
        return Result.success(null);
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
