package com.xander.lab.integration.host;

import com.xander.lab.common.Result;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform-owned public read boundary for vendor authorization state.
 *
 * <p>The Gateway removes caller-supplied identity headers and injects the trusted
 * {@code X-User-Id} header after validating the business access token. This controller is
 * enabled only after the Platform credential read cutover is staged; while disabled, the
 * compatibility proxy remains the owner of these routes.</p>
 */
@RestController
@RequestMapping("/api/publishing")
@ConditionalOnProperty(prefix = "platform-integration", name = "credential-store-enabled",
        havingValue = "true")
public class PlatformCredentialStatusController {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String UNAUTHORIZED_MESSAGE = "未登录或登录已过期";

    private final PlatformCredentialStatusStore credentialStore;

    public PlatformCredentialStatusController(PlatformCredentialStatusStore credentialStore) {
        this.credentialStore = credentialStore;
    }

    @GetMapping("/{platform:csdn|juejin}/authorization/status")
    public Result<AuthorizationView> status(
            @PathVariable String platform,
            @RequestHeader(value = USER_ID_HEADER, required = false) String userIdHeader) {
        Long userId = parseUserId(userIdHeader);
        if (userId == null) {
            return Result.unauthorized(UNAUTHORIZED_MESSAGE);
        }
        PlatformCredentialStatusStore.CredentialStatus status = credentialStore.status(platform, userId);
        return Result.success(new AuthorizationView(status.name(), null, null, null, null));
    }

    public record AuthorizationView(String status, String qrCodeDataUrl, Integer expiresInSeconds,
                                    Integer queuePosition, Integer estimatedWaitSeconds) {
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
