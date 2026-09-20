package com.xander.lab.identity.api;

import com.xander.lab.identity.application.AuthService;
import com.xander.lab.identity.application.SecurityStateSyncProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Agent 专用的内网访问令牌查询 API；默认关闭，不能作为公共鉴权入口。 */
@RestController
@RequestMapping("/internal/agent/auth")
@ConditionalOnProperty(prefix = "security.state-sync", name = "internal-api-enabled",
        havingValue = "true")
public class AgentAccessTokenController {
    private static final String INTERNAL_TOKEN_HEADER = "X-DinQor-Internal-Token";

    private final AuthService authService;
    private final SecurityStateSyncProperties properties;

    public AgentAccessTokenController(AuthService authService,
                                      SecurityStateSyncProperties properties) {
        this.authService = authService;
        this.properties = properties;
        if (properties.getInternalToken() == null || properties.getInternalToken().isBlank()) {
            throw new IllegalStateException(
                    "security.state-sync.internal-token 在启用内网同步 API 时不能为空");
        }
    }

    @PostMapping("/resolve")
    public ResolveResponse resolve(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String internalToken,
            @RequestBody(required = false) ResolveRequest request) {
        authorize(internalToken);
        return new ResolveResponse(authService.resolveUserId(
                request == null ? null : request.accessToken()));
    }

    private void authorize(String token) {
        if (token == null || !MessageDigest.isEqual(
                properties.getInternalToken().getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not Found");
        }
    }

    public record ResolveRequest(String accessToken) {
    }

    public record ResolveResponse(Long userId) {
    }
}
