package com.xander.lab.integration.internal.api;

import com.xander.lab.integration.internal.application.IntegrationInternalApiProperties;
import com.xander.lab.integration.mcp.juejin.application.JuejinCatalogService;
import com.xander.lab.integration.mcp.juejin.application.JuejinCredentialService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Token-protected explicit-owner boundary for the shared Juejin catalog refresh. */
@RestController
@RequestMapping("/internal/integration/platform-credentials")
@ConditionalOnProperty(prefix = "integration.internal", name = "api-enabled", havingValue = "true")
public class InternalJuejinCatalogController {

    private static final String INTERNAL_TOKEN_HEADER = "X-DinQor-Internal-Token";

    private final JuejinCatalogService catalogService;
    private final JuejinCredentialService credentialService;
    private final IntegrationInternalApiProperties properties;

    public InternalJuejinCatalogController(
            JuejinCatalogService catalogService,
            JuejinCredentialService credentialService,
            IntegrationInternalApiProperties properties) {
        this.catalogService = catalogService;
        this.credentialService = credentialService;
        this.properties = properties;
        if (properties.getInternalToken() == null || properties.getInternalToken().isBlank()) {
            throw new IllegalStateException(
                    "integration.internal.token 在启用掘金目录同步内网 API 时不能为空");
        }
    }

    @PostMapping("/{userId}/juejin/catalog/sync")
    public void sync(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @PathVariable Long userId) {
        authorize(token);
        validateUser(userId);
        catalogService.sync(credentialService.requireCredential(userId));
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
}
