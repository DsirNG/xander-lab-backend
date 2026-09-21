package com.xander.lab.integration.internal.api;

import com.xander.lab.integration.internal.application.IntegrationInternalApiProperties;
import com.xander.lab.integration.mcp.csdn.application.CsdnCredentialService;
import com.xander.lab.integration.mcp.juejin.application.JuejinCredentialService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Token-protected, explicit-owner credential status reads for platform bridges. */
@RestController
@RequestMapping("/internal/integration/platform-credentials")
@ConditionalOnProperty(prefix = "integration.internal", name = "api-enabled", havingValue = "true")
public class InternalPlatformCredentialStatusController {

    private static final String INTERNAL_TOKEN_HEADER = "X-DinQor-Internal-Token";

    private final CsdnCredentialService csdnCredentialService;
    private final JuejinCredentialService juejinCredentialService;
    private final IntegrationInternalApiProperties properties;

    public InternalPlatformCredentialStatusController(
            CsdnCredentialService csdnCredentialService,
            JuejinCredentialService juejinCredentialService,
            IntegrationInternalApiProperties properties) {
        this.csdnCredentialService = csdnCredentialService;
        this.juejinCredentialService = juejinCredentialService;
        this.properties = properties;
        if (properties.getInternalToken() == null || properties.getInternalToken().isBlank()) {
            throw new IllegalStateException(
                    "integration.internal.token 在启用平台凭证状态内网 API 时不能为空");
        }
    }

    @GetMapping("/{userId}/csdn/status")
    public CredentialStatusResponse csdnStatus(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @PathVariable Long userId) {
        authorize(token);
        validateUser(userId);
        return new CredentialStatusResponse(csdnCredentialService.isAuthorized(userId));
    }

    @GetMapping("/{userId}/juejin/status")
    public CredentialStatusResponse juejinStatus(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @PathVariable Long userId) {
        authorize(token);
        validateUser(userId);
        return new CredentialStatusResponse(juejinCredentialService.isAuthorized(userId));
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

    public record CredentialStatusResponse(boolean authorized) {
    }
}
