package com.xander.lab.automation.host;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.util.StreamUtils;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;

/**
 * Streaming-safe compatibility bridge for the not-yet-cut BlogAgent and automation routes.
 *
 * <p>The response body is copied directly to the servlet response instead of being buffered in
 * memory. This is required for the notification SSE route; a compatibility boundary must not
 * turn a never-ending stream into a byte array.</p>
 */
@RestController
public class ContentAutomationProxyController {

    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length");

    private final RestClient restClient;
    private final ContentAutomationProperties properties;
    private final ContentAutomationStreamState streamState;

    public ContentAutomationProxyController(RestClient.Builder restClientBuilder,
                                            ContentAutomationProperties properties,
                                            ContentAutomationStreamState streamState) {
        this.restClient = restClientBuilder.build();
        this.properties = properties;
        this.streamState = streamState;
    }

    @RequestMapping({
            "/api/blog-plans/**"
    })
    public void proxy(HttpServletRequest request,
                      HttpServletResponse response) {
        boolean stream = isStreamRequest(request);
        if (stream && properties.isStreamDrainEnabled()) {
            response.setStatus(503);
            response.setHeader("Retry-After", "1");
            return;
        }
        if (stream) {
            streamState.opened();
        }
        try {
            byte[] requestBody = StreamUtils.copyToByteArray(request.getInputStream());
            RestClient.RequestBodySpec spec = restClient
                    .method(HttpMethod.valueOf(request.getMethod()))
                    .uri(targetUri(request));
            copyRequestHeaders(request, spec);
            spec.body(requestBody).exchange((clientRequest, clientResponse) -> {
                response.setStatus(clientResponse.getStatusCode().value());
                copyResponseHeaders(clientResponse.getHeaders(), response);
                StreamUtils.copy(clientResponse.getBody(), response.getOutputStream());
                response.flushBuffer();
                return null;
            });
        } catch (Exception exception) {
            if (response.isCommitted()) {
                throw new IllegalStateException("内容自动化兼容流已提交后失败", exception);
            }
            throw new ResponseStatusException(
                    BAD_GATEWAY,
                    "内容自动化服务暂时无法连接 Core 兼容边界",
                    exception);
        } finally {
            if (stream) {
                streamState.closed();
            }
        }
    }

    private boolean isStreamRequest(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.endsWith("/events") || path.endsWith("/stream");
    }

    private URI targetUri(HttpServletRequest request) {
        String baseUrl = properties.getCoreBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("content-automation.core-base-url 不能为空");
        }
        String query = request.getQueryString();
        return URI.create(baseUrl.replaceAll("/+$", "") + request.getRequestURI()
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

    private void copyResponseHeaders(HttpHeaders source,
                                     HttpServletResponse response) {
        source.forEach((name, values) -> {
            if (!HOP_BY_HOP_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                for (String value : values) {
                    response.addHeader(name, value);
                }
            }
        });
        if (response.getContentType() == null) {
            response.setContentType("application/octet-stream");
        }
    }
}
