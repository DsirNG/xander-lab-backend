package com.xander.lab.integration.host;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;

/** Bridges the Platform-owned Juejin catalog command to Core during compatibility drain. */
@RestController
@RequestMapping("/internal/platform-integration/platform-credentials")
@ConditionalOnProperty(prefix = "platform-integration", name = "internal-api-enabled",
        havingValue = "true")
public class PlatformIntegrationInternalCatalogController {

    private static final String INTERNAL_TOKEN_HEADER = "X-DinQor-Internal-Token";

    private final RestClient restClient;
    private final PlatformIntegrationProperties properties;

    public PlatformIntegrationInternalCatalogController(
            RestClient.Builder restClientBuilder,
            PlatformIntegrationProperties properties) {
        if (properties.getInternalToken() == null || properties.getInternalToken().isBlank()) {
            throw new IllegalStateException(
                    "platform-integration.internal-token 在启用掘金目录内网 API 时不能为空");
        }
        if (properties.getCoreInternalToken() == null || properties.getCoreInternalToken().isBlank()) {
            throw new IllegalStateException(
                    "platform-integration.core-internal-token 在启用掘金目录内网 API 时不能为空");
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(timeout(properties.getConnectTimeoutMillis())));
        requestFactory.setReadTimeout(Duration.ofMillis(timeout(properties.getReadTimeoutMillis())));
        this.restClient = restClientBuilder.requestFactory(requestFactory).build();
        this.properties = properties;
    }

    @PostMapping("/{userId}/juejin/catalog/sync")
    public void sync(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @PathVariable Long userId) {
        authorize(token);
        validateUser(userId);
        try {
            ResponseEntity<Void> response = restClient.post()
                    .uri(targetUri("/internal/integration/platform-credentials/" + userId
                            + "/juejin/catalog/sync"))
                    .header(INTERNAL_TOKEN_HEADER, properties.getCoreInternalToken())
                    .retrieve()
                    .toBodilessEntity();
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new ResponseStatusException(BAD_GATEWAY,
                        "平台集成服务暂时无法完成掘金目录同步");
            }
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(BAD_GATEWAY,
                    "平台集成服务暂时无法连接 Core 掘金目录边界", exception);
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

    private void validateUser(Long userId) {
        if (userId == null || userId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "凭证属主参数非法");
        }
    }

    private int timeout(int value) {
        return Math.max(1, value);
    }
}
