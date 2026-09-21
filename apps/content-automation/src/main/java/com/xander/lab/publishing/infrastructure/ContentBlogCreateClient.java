package com.xander.lab.publishing.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.automation.host.ContentAutomationProperties;
import com.xander.lab.publishing.api.BlogPostDTO;
import com.xander.lab.publishing.api.BlogPostVO;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;

/** Calls the Core compatibility boundary while the publishing write drains. */
@Component
public class ContentBlogCreateClient {

    private static final String INTERNAL_TOKEN_HEADER = "X-DinQor-Internal-Token";

    private final RestClient restClient;
    private final ContentAutomationProperties properties;
    private final ObjectMapper objectMapper;

    public ContentBlogCreateClient(RestClient.Builder restClientBuilder,
                                   ContentAutomationProperties properties,
                                   ObjectMapper objectMapper) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(timeout(properties.getCoreConnectTimeoutMillis())));
        requestFactory.setReadTimeout(Duration.ofMillis(timeout(properties.getCoreReadTimeoutMillis())));
        this.restClient = restClientBuilder.requestFactory(requestFactory).build();
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public BlogPostVO create(long userId, BlogPostDTO request, boolean publish, String requestId) {
        if (properties.getCoreInternalToken() == null || properties.getCoreInternalToken().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "内容自动化服务未配置 Core 内部令牌");
        }
        InternalCreateRequest payload = new InternalCreateRequest(userId, request.getTitle(),
                request.getSummary(), request.getContent(), request.getCategoryId(),
                request.getCoverImage(), request.getTags(), publish, requestId);
        try {
            byte[] body = restClient.post()
                    .uri(targetUri())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(INTERNAL_TOKEN_HEADER, properties.getCoreInternalToken())
                    .body(payload)
                    .exchange((clientRequest, clientResponse) -> {
                        if (!clientResponse.getStatusCode().is2xxSuccessful()) {
                            return new byte[0];
                        }
                        return copyBounded(clientResponse.getBody(), properties.getCoreMaxResponseBytes());
                    });
            if (body == null || body.length == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Core 内容写入边界返回了空响应");
            }
            return objectMapper.readValue(body, BlogPostVO.class);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "内容自动化服务暂时无法连接 Core 内容写入边界", exception);
        }
    }

    private URI targetUri() {
        String baseUrl = properties.getCoreBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("content-automation.core-base-url 不能为空");
        }
        return URI.create(baseUrl.replaceAll("/+$", "") + "/internal/content/blog-posts");
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
                throw new IOException("Core 内容写入响应超过允许大小");
            }
            output.write(buffer, 0, read);
            total += read;
        }
        return output.toByteArray();
    }

    private int timeout(int value) {
        return Math.max(1, value);
    }

    private record InternalCreateRequest(Long userId, String title, String summary, String content,
                                         String categoryId, String coverImage,
                                         java.util.List<String> tags, boolean publish,
                                         String requestId) {
    }

}
