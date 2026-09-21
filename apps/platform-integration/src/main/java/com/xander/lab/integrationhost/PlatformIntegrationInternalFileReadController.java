package com.xander.lab.integration.host;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;

/** Bridges Platform-owned file reads to the Core compatibility executor. */
@RestController
@RequestMapping("/internal/platform-integration/files")
@ConditionalOnProperty(prefix = "platform-integration", name = "internal-api-enabled",
        havingValue = "true")
public class PlatformIntegrationInternalFileReadController {

    private static final String INTERNAL_TOKEN_HEADER = "X-DinQor-Internal-Token";
    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length");

    private final RestClient restClient;
    private final PlatformIntegrationProperties properties;

    public PlatformIntegrationInternalFileReadController(
            RestClient.Builder restClientBuilder,
            PlatformIntegrationProperties properties) {
        if (properties.getInternalToken() == null || properties.getInternalToken().isBlank()) {
            throw new IllegalStateException(
                    "platform-integration.internal-token 在启用文件读取内网 API 时不能为空");
        }
        if (properties.getCoreInternalToken() == null || properties.getCoreInternalToken().isBlank()) {
            throw new IllegalStateException(
                    "platform-integration.core-internal-token 在启用文件读取内网 API 时不能为空");
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(timeout(properties.getConnectTimeoutMillis())));
        requestFactory.setReadTimeout(Duration.ofMillis(timeout(properties.getReadTimeoutMillis())));
        this.restClient = restClientBuilder.requestFactory(requestFactory).build();
        this.properties = properties;
    }

    @GetMapping("/download/{filename:.+}")
    public ResponseEntity<byte[]> download(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @PathVariable String filename) {
        authorize(token);
        return forward("/internal/integration/files/download/" + filename, null);
    }

    @GetMapping("/export/csv")
    public ResponseEntity<byte[]> exportCsv(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @RequestParam(defaultValue = "blog") String type) {
        authorize(token);
        return forward("/internal/integration/files/export/csv", type);
    }

    @GetMapping("/export/excel")
    public ResponseEntity<byte[]> exportExcel(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @RequestParam(defaultValue = "blog") String type) {
        authorize(token);
        return forward("/internal/integration/files/export/excel", type);
    }

    private ResponseEntity<byte[]> forward(String path, String type) {
        try {
            URI uri = targetUri(path, type);
            ResponseEntity<byte[]> response = restClient.method(HttpMethod.GET)
                    .uri(uri)
                    .header(INTERNAL_TOKEN_HEADER, properties.getCoreInternalToken())
                    .exchange((clientRequest, clientResponse) -> new ResponseEntity<>(
                            copyBounded(clientResponse.getBody(), properties.getMaxResponseBytes()),
                            copyHeaders(clientResponse.getHeaders()),
                            clientResponse.getStatusCode()));
            return response;
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(BAD_GATEWAY,
                    "平台集成服务暂时无法连接 Core 文件读取边界", exception);
        }
    }

    private URI targetUri(String path, String type) {
        String baseUrl = properties.getCoreBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("platform-integration.core-base-url 不能为空");
        }
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString(baseUrl.replaceAll("/+$", "") + path);
        if (type != null) {
            builder.queryParam("type", type);
        }
        return builder.build().toUri();
    }

    private HttpHeaders copyHeaders(HttpHeaders source) {
        HttpHeaders headers = new HttpHeaders();
        source.forEach((name, values) -> {
            if (!HOP_BY_HOP_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                headers.put(name, values);
            }
        });
        if (!headers.containsKey(HttpHeaders.CONTENT_TYPE)) {
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        }
        return headers;
    }

    private void authorize(String token) {
        if (token == null || !MessageDigest.isEqual(
                properties.getInternalToken().getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not Found");
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
                throw new IOException("文件读取响应超过允许大小");
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
