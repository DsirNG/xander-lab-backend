package com.xander.lab.automation.infrastructure;

import com.xander.lab.automation.host.ContentAutomationProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;

/** Reads integration-owned authorization state without taking ownership of credential tables. */
@Slf4j
@Component
public class PlatformCredentialStatusClient {

    private final RestClient restClient;
    private final ContentAutomationProperties properties;
    private final ObjectMapper objectMapper;

    public PlatformCredentialStatusClient(RestClient.Builder restClientBuilder,
                                          ContentAutomationProperties properties,
                                          ObjectMapper objectMapper) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(timeout(
                properties.getPlatformIntegrationConnectTimeoutMillis())));
        requestFactory.setReadTimeout(Duration.ofMillis(timeout(
                properties.getPlatformIntegrationReadTimeoutMillis())));
        this.restClient = restClientBuilder.requestFactory(requestFactory).build();
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public boolean isAuthorized(String platform, Long userId, String authorization) {
        if (internalConfigured()) {
            return isAuthorizedInternally(platform, userId);
        }
        return isAuthorizedThroughPublicApi(platform, authorization);
    }

    private boolean isAuthorizedThroughPublicApi(String platform, String authorization) {
        if (!validPlatform(platform) || baseUrl() == null
                || authorization == null || authorization.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(baseUrl()
                    + "/api/publishing/" + platform + "/authorization/status");
            ResponseEntity<byte[]> response = restClient.get()
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, authorization)
                    .exchange((request, clientResponse) -> {
                        try {
                            return new ResponseEntity<>(
                                    clientResponse.getStatusCode().is2xxSuccessful()
                                            ? copyBounded(clientResponse.getBody(),
                                            properties.getPlatformIntegrationMaxResponseBytes())
                                            : new byte[0],
                                    clientResponse.getStatusCode());
                        } catch (IOException exception) {
                            throw new IllegalStateException("平台授权状态响应读取失败", exception);
                        }
                    });
            if (!response.getStatusCode().is2xxSuccessful()
                    || response.getBody() == null || response.getBody().length == 0) {
                return false;
            }
            JsonNode body = objectMapper.readTree(response.getBody());
            return body != null && "AUTHORIZED".equals(body.path("data").path("status").asText());
        } catch (IOException exception) {
            log.warn("[BlogPlan] credential status fail-soft platform={} reason={}",
                    platform, rootMessage(exception));
            return false;
        } catch (RuntimeException exception) {
            log.warn("[BlogPlan] credential status fail-soft platform={} reason={}",
                    platform, rootMessage(exception));
            return false;
        }
    }

    private boolean isAuthorizedInternally(String platform, Long userId) {
        if (!validPlatform(platform) || userId == null || userId <= 0) {
            return false;
        }
        try {
            URI uri = URI.create(baseUrl() + "/internal/platform-integration/platform-credentials/"
                    + userId + "/" + platform + "/status");
            ResponseEntity<byte[]> response = restClient.get()
                    .uri(uri)
                    .header("X-DinQor-Internal-Token", properties.getPlatformIntegrationInternalToken())
                    .exchange((request, clientResponse) -> {
                        try {
                            return new ResponseEntity<>(
                                    clientResponse.getStatusCode().is2xxSuccessful()
                                            ? copyBounded(clientResponse.getBody(),
                                            properties.getPlatformIntegrationMaxResponseBytes())
                                            : new byte[0],
                                    clientResponse.getStatusCode());
                        } catch (IOException exception) {
                            throw new IllegalStateException("平台凭证状态响应读取失败", exception);
                        }
                    });
            if (!response.getStatusCode().is2xxSuccessful()
                    || response.getBody() == null || response.getBody().length == 0) {
                return false;
            }
            return objectMapper.readTree(response.getBody()).path("authorized").asBoolean(false);
        } catch (IOException | RuntimeException exception) {
            log.warn("[BlogPlan] internal credential status fail-soft platform={} user={} reason={}",
                    platform, userId, rootMessage(exception));
            return false;
        }
    }

    private boolean internalConfigured() {
        return baseUrl() != null
                && properties.getPlatformIntegrationInternalToken() != null
                && !properties.getPlatformIntegrationInternalToken().isBlank();
    }

    private String baseUrl() {
        String baseUrl = properties.getPlatformIntegrationBaseUrl();
        return baseUrl == null || baseUrl.isBlank() ? null : baseUrl.replaceAll("/+$", "");
    }

    private boolean validPlatform(String platform) {
        return "csdn".equals(platform) || "juejin".equals(platform);
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
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
                throw new IOException("平台授权状态响应超过允许大小");
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
