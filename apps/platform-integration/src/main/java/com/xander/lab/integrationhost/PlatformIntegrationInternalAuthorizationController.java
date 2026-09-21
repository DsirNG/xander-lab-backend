package com.xander.lab.integration.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

import static org.springframework.http.HttpStatus.BAD_GATEWAY;

/** Bridges Platform authorization commands to the Core compatibility executor. */
@RestController
@RequestMapping("/internal/platform-integration/platform-credentials")
@ConditionalOnProperty(prefix = "platform-integration", name = "internal-api-enabled",
        havingValue = "true")
public class PlatformIntegrationInternalAuthorizationController {

    private static final String INTERNAL_TOKEN_HEADER = "X-DinQor-Internal-Token";

    private final RestClient restClient;
    private final PlatformIntegrationProperties properties;
    private final ObjectMapper objectMapper;

    public PlatformIntegrationInternalAuthorizationController(
            RestClient.Builder restClientBuilder,
            PlatformIntegrationProperties properties,
            ObjectMapper objectMapper) {
        if (properties.getInternalToken() == null || properties.getInternalToken().isBlank()) {
            throw new IllegalStateException(
                    "platform-integration.internal-token 在启用平台授权命令内网 API 时不能为空");
        }
        if (properties.getCoreInternalToken() == null || properties.getCoreInternalToken().isBlank()) {
            throw new IllegalStateException(
                    "platform-integration.core-internal-token 在启用平台授权命令内网 API 时不能为空");
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(timeout(properties.getConnectTimeoutMillis())));
        requestFactory.setReadTimeout(Duration.ofMillis(timeout(properties.getReadTimeoutMillis())));
        this.restClient = restClientBuilder.requestFactory(requestFactory).build();
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/{userId}/{platform:csdn|juejin}/authorization/start")
    public AuthorizationResponse start(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @PathVariable Long userId,
            @PathVariable String platform) {
        authorize(token);
        validateUser(userId);
        return forwardStart(userId, platform);
    }

    @PostMapping("/{userId}/{platform:csdn|juejin}/authorization/cancel")
    public void cancel(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @PathVariable Long userId,
            @PathVariable String platform) {
        authorize(token);
        validateUser(userId);
        forwardCommand(userId, platform, "cancel", false);
    }

    @DeleteMapping("/{userId}/{platform:csdn|juejin}/authorization")
    public void disconnect(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @PathVariable Long userId,
            @PathVariable String platform) {
        authorize(token);
        validateUser(userId);
        forwardCommand(userId, platform, "", true);
    }

    private AuthorizationResponse forwardStart(Long userId, String platform) {
        try {
            ResponseEntity<byte[]> response = restClient.post()
                    .uri(targetUri(corePath(userId, platform, "start")))
                    .header(INTERNAL_TOKEN_HEADER, properties.getCoreInternalToken())
                    .exchange((clientRequest, clientResponse) -> new ResponseEntity<>(
                            clientResponse.getStatusCode().is2xxSuccessful()
                                    ? copyBounded(clientResponse.getBody(), properties.getMaxResponseBytes())
                                    : new byte[0],
                            clientResponse.getStatusCode()));
            requireSuccess(response, "平台授权启动");
            if (response.getBody() == null || response.getBody().length == 0) {
                throw new ResponseStatusException(BAD_GATEWAY, "平台授权执行器返回了空结果");
            }
            return objectMapper.readValue(response.getBody(), AuthorizationResponse.class);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(BAD_GATEWAY,
                    "平台集成服务暂时无法连接 Core 授权执行边界", exception);
        }
    }

    private void forwardCommand(Long userId, String platform, String command, boolean delete) {
        try {
            var request = delete ? restClient.delete() : restClient.post();
            ResponseEntity<byte[]> response = request
                    .uri(targetUri(corePath(userId, platform, command)))
                    .header(INTERNAL_TOKEN_HEADER, properties.getCoreInternalToken())
                    .exchange((clientRequest, clientResponse) -> new ResponseEntity<>(
                            clientResponse.getStatusCode().is2xxSuccessful()
                                    ? copyBounded(clientResponse.getBody(), properties.getMaxResponseBytes())
                                    : new byte[0],
                            clientResponse.getStatusCode()));
            requireSuccess(response, "平台授权命令");
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(BAD_GATEWAY,
                    "平台集成服务暂时无法连接 Core 授权执行边界", exception);
        }
    }

    private void requireSuccess(ResponseEntity<byte[]> response, String operation) {
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new ResponseStatusException(BAD_GATEWAY,
                    "平台集成服务暂时无法完成" + operation);
        }
    }

    private String corePath(Long userId, String platform, String command) {
        String suffix = command.isBlank() ? "" : "/" + command;
        return "/internal/integration/platform-credentials/" + userId + "/" + platform
                + "/authorization" + suffix;
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
                throw new IOException("平台授权响应超过允许大小");
            }
            output.write(buffer, 0, read);
            total += read;
        }
        return output.toByteArray();
    }

    private int timeout(int value) {
        return Math.max(1, value);
    }

    public record AuthorizationResponse(String status, String qrCodeDataUrl,
                                        Integer expiresInSeconds, Integer queuePosition,
                                        Integer estimatedWaitSeconds) {
    }
}
