package com.xander.lab.integration.internal.api;

import com.xander.lab.integration.internal.application.IntegrationInternalApiProperties;
import com.xander.lab.integration.mcp.csdn.application.CsdnAuthorizationService;
import com.xander.lab.integration.mcp.juejin.application.JuejinAuthorizationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Token-protected command boundary for Platform-owned authorization entry points.
 *
 * <p>The user id is explicit because this is a service-to-service boundary. The Selenium
 * session and credential write remain in Core during the compatibility phase; Platform owns
 * the public route and calls this boundary through a single execution path.</p>
 */
@RestController
@RequestMapping("/internal/integration/platform-credentials")
@ConditionalOnProperty(prefix = "integration.internal", name = "api-enabled", havingValue = "true")
public class InternalPlatformAuthorizationController {

    private static final String INTERNAL_TOKEN_HEADER = "X-DinQor-Internal-Token";

    private final CsdnAuthorizationService csdnAuthorizationService;
    private final JuejinAuthorizationService juejinAuthorizationService;
    private final IntegrationInternalApiProperties properties;

    public InternalPlatformAuthorizationController(
            CsdnAuthorizationService csdnAuthorizationService,
            JuejinAuthorizationService juejinAuthorizationService,
            IntegrationInternalApiProperties properties) {
        this.csdnAuthorizationService = csdnAuthorizationService;
        this.juejinAuthorizationService = juejinAuthorizationService;
        this.properties = properties;
        if (properties.getInternalToken() == null || properties.getInternalToken().isBlank()) {
            throw new IllegalStateException(
                    "integration.internal.token 在启用平台授权命令内网 API 时不能为空");
        }
    }

    @PostMapping("/{userId}/csdn/authorization/start")
    public AuthorizationResponse csdnStart(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @PathVariable Long userId) {
        authorize(token);
        validateUser(userId);
        return AuthorizationResponse.from(csdnAuthorizationService.start(userId));
    }

    @PostMapping("/{userId}/juejin/authorization/start")
    public AuthorizationResponse juejinStart(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @PathVariable Long userId) {
        authorize(token);
        validateUser(userId);
        return AuthorizationResponse.from(juejinAuthorizationService.start(userId));
    }

    @PostMapping("/{userId}/csdn/authorization/cancel")
    public void csdnCancel(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @PathVariable Long userId) {
        authorize(token);
        validateUser(userId);
        csdnAuthorizationService.cancel(userId);
    }

    @PostMapping("/{userId}/juejin/authorization/cancel")
    public void juejinCancel(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @PathVariable Long userId) {
        authorize(token);
        validateUser(userId);
        juejinAuthorizationService.cancel(userId);
    }

    @DeleteMapping("/{userId}/csdn/authorization")
    public void csdnDisconnect(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @PathVariable Long userId) {
        authorize(token);
        validateUser(userId);
        csdnAuthorizationService.disconnect(userId);
    }

    @DeleteMapping("/{userId}/juejin/authorization")
    public void juejinDisconnect(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @PathVariable Long userId) {
        authorize(token);
        validateUser(userId);
        juejinAuthorizationService.disconnect(userId);
    }

    private void authorize(String token) {
        if (token == null || !MessageDigest.isEqual(
                properties.getInternalToken().getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not Found");
        }
    }

    private void validateUser(Long userId) {
        if (userId == null || userId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "凭证属主参数非法");
        }
    }

    /** Public-compatible shape without exposing cookie or other credential material. */
    public record AuthorizationResponse(String status, String qrCodeDataUrl,
                                        Integer expiresInSeconds, Integer queuePosition,
                                        Integer estimatedWaitSeconds) {
        static AuthorizationResponse from(CsdnAuthorizationService.AuthorizationView view) {
            return new AuthorizationResponse(view.status(), view.qrCodeDataUrl(),
                    view.expiresInSeconds(), view.queuePosition(), view.estimatedWaitSeconds());
        }

        static AuthorizationResponse from(JuejinAuthorizationService.AuthorizationView view) {
            return new AuthorizationResponse(view.status(), view.qrCodeDataUrl(),
                    view.expiresInSeconds(), view.queuePosition(), view.estimatedWaitSeconds());
        }
    }
}
