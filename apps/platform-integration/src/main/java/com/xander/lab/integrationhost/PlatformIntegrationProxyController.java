package com.xander.lab.integration.host;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;

/**
 * Compatibility bridge for the integration route set.
 *
 * <p>Only integration-owned public prefixes are mapped here. The bridge preserves the original
 * path, query string, request body and end-user headers, so it does not invent a second API. Its
 * target is explicitly configurable and defaults to Core for the pre-cutover state.</p>
 */
@RestController
public class PlatformIntegrationProxyController {

    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length");

    private final RestClient restClient;
    private final PlatformIntegrationProperties properties;

    public PlatformIntegrationProxyController(RestClient.Builder restClientBuilder,
                                              PlatformIntegrationProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(timeout(properties.getConnectTimeoutMillis())));
        requestFactory.setReadTimeout(Duration.ofMillis(timeout(properties.getReadTimeoutMillis())));
        this.restClient = restClientBuilder.requestFactory(requestFactory).build();
        this.properties = properties;
    }

    @RequestMapping({
            "/.well-known/**",
            "/api/blog/posts/*/sync/**",
            "/api/download/**",
            "/api/export/**",
            "/api/mcp/**",
            "/api/publishing/csdn/**",
            "/api/publishing/juejin/**",
            "/api/upload/**",
            "/mcp/**"
    })
    public ResponseEntity<byte[]> proxy(HttpServletRequest request,
        HttpServletResponse ignoredResponse) {
        try {
            byte[] requestBody = copyBounded(request.getInputStream(), properties.getMaxResponseBytes());
            URI target = targetUri(request);
            RestClient.RequestBodySpec spec = restClient.method(HttpMethod.valueOf(request.getMethod()))
                    .uri(target);
            copyRequestHeaders(request, spec);
            ResponseEntity<byte[]> response = spec.body(requestBody).exchange((clientRequest,
                                                                                clientResponse) ->
                    new ResponseEntity<>(
                            copyBounded(clientResponse.getBody(), properties.getMaxResponseBytes()),
                            clientResponse.getHeaders(),
                            clientResponse.getStatusCode()));
            return copyResponseHeaders(response);
        } catch (Exception exception) {
            throw new ResponseStatusException(
                    BAD_GATEWAY,
                    "平台集成服务暂时无法连接 Core 兼容边界",
                    exception);
        }
    }

    private URI targetUri(HttpServletRequest request) {
        String baseUrl = properties.getCoreBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("platform-integration.core-base-url 不能为空");
        }
        String path = request.getRequestURI();
        String query = request.getQueryString();
        return URI.create(baseUrl.replaceAll("/+$", "") + path
                + (query == null || query.isBlank() ? "" : "?" + query));
    }

    private void copyRequestHeaders(HttpServletRequest request,
                                    RestClient.RequestHeadersSpec<?> spec) {
        var headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            if (HOP_BY_HOP_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            var values = request.getHeaders(name);
            while (values.hasMoreElements()) {
                spec.header(name, values.nextElement());
            }
        }
    }

    private ResponseEntity<byte[]> copyResponseHeaders(ResponseEntity<byte[]> response) {
        HttpHeaders headers = new HttpHeaders();
        response.getHeaders().forEach((name, values) -> {
            if (!HOP_BY_HOP_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                headers.put(name, values);
            }
        });
        if (!headers.containsKey(HttpHeaders.CONTENT_TYPE)) {
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        }
        return new ResponseEntity<>(response.getBody(), headers, response.getStatusCode());
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
                throw new IOException("集成响应超过允许大小");
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
