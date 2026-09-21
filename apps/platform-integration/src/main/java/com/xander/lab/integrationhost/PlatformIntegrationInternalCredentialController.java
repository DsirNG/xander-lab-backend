package com.xander.lab.integration.host;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Optional;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;

/** Bridges explicit-owner credential status reads to the legacy Core owner. */
@RestController
@RequestMapping("/internal/platform-integration/platform-credentials")
@ConditionalOnProperty(prefix = "platform-integration", name = "internal-api-enabled",
        havingValue = "true")
public class PlatformIntegrationInternalCredentialController {

    private static final String INTERNAL_TOKEN_HEADER = "X-DinQor-Internal-Token";

    private final RestClient restClient;
    private final PlatformIntegrationProperties properties;
    private final Optional<PlatformCredentialStatusStore> credentialStore;

    @Autowired
    public PlatformIntegrationInternalCredentialController(RestClient.Builder restClientBuilder,
                                                            PlatformIntegrationProperties properties,
                                                            Optional<PlatformCredentialStatusStore> credentialStore) {
        if (properties.getInternalToken() == null || properties.getInternalToken().isBlank()) {
            throw new IllegalStateException(
                    "platform-integration.internal-token 在启用平台凭证状态内网 API 时不能为空");
        }
        if (properties.getCoreInternalToken() == null || properties.getCoreInternalToken().isBlank()) {
            throw new IllegalStateException(
                    "platform-integration.core-internal-token 在启用平台凭证状态内网 API 时不能为空");
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(timeout(properties.getConnectTimeoutMillis())));
        requestFactory.setReadTimeout(Duration.ofMillis(timeout(properties.getReadTimeoutMillis())));
        this.restClient = restClientBuilder.requestFactory(requestFactory).build();
        this.properties = properties;
        this.credentialStore = credentialStore;
    }

    /** Compatibility constructor for tests and bridge-only deployments. */
    public PlatformIntegrationInternalCredentialController(RestClient.Builder restClientBuilder,
                                                            PlatformIntegrationProperties properties) {
        this(restClientBuilder, properties, Optional.empty());
    }

    @GetMapping("/{userId}/csdn/status")
    public ResponseEntity<byte[]> csdnStatus(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @PathVariable Long userId) {
        authorize(token);
        validateUser(userId);
        if (credentialStore.isPresent()) {
            return localStatus("csdn", userId);
        }
        return forward("/internal/integration/platform-credentials/" + userId + "/csdn/status");
    }

    @GetMapping("/{userId}/juejin/status")
    public ResponseEntity<byte[]> juejinStatus(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @PathVariable Long userId) {
        authorize(token);
        validateUser(userId);
        if (credentialStore.isPresent()) {
            return localStatus("juejin", userId);
        }
        return forward("/internal/integration/platform-credentials/" + userId + "/juejin/status");
    }

    private ResponseEntity<byte[]> localStatus(String platform, Long userId) {
        boolean authorized = credentialStore.get().isAuthorized(platform, userId);
        return ResponseEntity.ok()
                .header("Content-Type", "application/json")
                .body(("{\"authorized\":" + authorized + "}")
                        .getBytes(StandardCharsets.UTF_8));
    }

    private ResponseEntity<byte[]> forward(String path) {
        try {
            ResponseEntity<byte[]> response = restClient.get()
                    .uri(targetUri(path))
                    .header(INTERNAL_TOKEN_HEADER, properties.getCoreInternalToken())
                    .exchange((clientRequest, clientResponse) -> new ResponseEntity<>(
                            clientResponse.getStatusCode().is2xxSuccessful()
                                    ? copyBounded(clientResponse.getBody(), properties.getMaxResponseBytes())
                                    : new byte[0],
                            clientResponse.getStatusCode()));
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new ResponseStatusException(BAD_GATEWAY,
                        "平台集成服务暂时无法读取凭证状态");
            }
            return response;
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(BAD_GATEWAY,
                    "平台集成服务暂时无法连接 Core 凭证边界", exception);
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

    private byte[] copyBounded(InputStream input, int configuredLimit) throws IOException {
        if (input == null) {
            return new byte[0];
        }
        int limit = Math.max(1, configuredLimit);
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (read > limit - total) {
                throw new IOException("凭证状态响应超过允许大小");
            }
            output.write(buffer, 0, read);
            total += read;
        }
        return output.toByteArray();
    }

    private int timeout(int value) {
        return Math.max(1, value);
    }
}
