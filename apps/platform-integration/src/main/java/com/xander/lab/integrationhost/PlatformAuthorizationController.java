package com.xander.lab.integration.host;

import com.xander.lab.common.Result;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/** Platform-owned public command boundary for CSDN and Juejin authorization. */
@RestController
@RequestMapping("/api/publishing")
@ConditionalOnProperty(prefix = "platform-integration", name = "internal-api-enabled",
        havingValue = "true")
public class PlatformAuthorizationController {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String UNAUTHORIZED_MESSAGE = "未登录或登录已过期";

    private final PlatformIntegrationInternalAuthorizationController authorizationBridge;
    private final PlatformIntegrationProperties properties;
    private final Optional<PlatformCsdnAuthorizationService> localCsdnAuthorization;

    @Autowired
    public PlatformAuthorizationController(
            PlatformIntegrationInternalAuthorizationController authorizationBridge,
            PlatformIntegrationProperties properties,
            Optional<PlatformCsdnAuthorizationService> localCsdnAuthorization) {
        this.authorizationBridge = authorizationBridge;
        this.properties = properties;
        this.localCsdnAuthorization = localCsdnAuthorization;
    }

    /** Compatibility constructor retained for controller unit tests and bridge-only deployments. */
    public PlatformAuthorizationController(
            PlatformIntegrationInternalAuthorizationController authorizationBridge,
            PlatformIntegrationProperties properties) {
        this(authorizationBridge, properties, Optional.empty());
    }

    @PostMapping("/{platform:csdn|juejin}/authorization/start")
    public Result<?> start(
            @PathVariable String platform,
            @RequestHeader(value = USER_ID_HEADER, required = false) String userIdHeader) {
        Long userId = parseUserId(userIdHeader);
        if (userId == null) {
            return Result.unauthorized(UNAUTHORIZED_MESSAGE);
        }
        if ("csdn".equals(platform) && localCsdnAuthorization.isPresent()) {
            return Result.success(localCsdnAuthorization.get().start(userId));
        }
        return Result.success(authorizationBridge.start(properties.getInternalToken(), userId, platform));
    }

    @PostMapping("/{platform:csdn|juejin}/authorization/cancel")
    public Result<Void> cancel(
            @PathVariable String platform,
            @RequestHeader(value = USER_ID_HEADER, required = false) String userIdHeader) {
        Long userId = parseUserId(userIdHeader);
        if (userId == null) {
            return Result.unauthorized(UNAUTHORIZED_MESSAGE);
        }
        if ("csdn".equals(platform) && localCsdnAuthorization.isPresent()) {
            localCsdnAuthorization.get().cancel(userId);
            return Result.success(null);
        }
        authorizationBridge.cancel(properties.getInternalToken(), userId, platform);
        return Result.success(null);
    }

    @DeleteMapping("/{platform:csdn|juejin}/authorization")
    public Result<Void> disconnect(
            @PathVariable String platform,
            @RequestHeader(value = USER_ID_HEADER, required = false) String userIdHeader) {
        Long userId = parseUserId(userIdHeader);
        if (userId == null) {
            return Result.unauthorized(UNAUTHORIZED_MESSAGE);
        }
        if ("csdn".equals(platform) && localCsdnAuthorization.isPresent()) {
            localCsdnAuthorization.get().disconnect(userId);
            return Result.success(null);
        }
        authorizationBridge.disconnect(properties.getInternalToken(), userId, platform);
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
