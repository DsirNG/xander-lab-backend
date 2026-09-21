package com.xander.lab.publishing.api;

import com.xander.lab.content.api.ContentPost;
import com.xander.lab.content.api.ContentPostCreationPort;
import com.xander.lab.content.api.ContentPostInput;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.beans.factory.annotation.Value;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/** Token-protected Core execution boundary for the extracted publishing create entry. */
@RestController
@RequestMapping("/internal/content/blog-posts")
@ConditionalOnProperty(prefix = "integration.internal", name = "api-enabled", havingValue = "true")
public class InternalBlogPostCreateController {

    private static final String INTERNAL_TOKEN_HEADER = "X-DinQor-Internal-Token";

    private final ContentPostCreationPort contentPostCreationPort;
    private final String internalToken;

    public InternalBlogPostCreateController(ContentPostCreationPort contentPostCreationPort,
                                            @Value("${integration.internal.internal-token:}") String internalToken) {
        this.contentPostCreationPort = contentPostCreationPort;
        this.internalToken = internalToken;
        if (internalToken == null || internalToken.isBlank()) {
            throw new IllegalStateException(
                    "integration.internal.internal-token 在启用内容写入内网 API 时不能为空");
        }
    }

    @PostMapping
    public ResponseEntity<ContentPost> create(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @RequestBody CreateRequest request) {
        authorize(token);
        if (request == null || request.userId() == null || request.userId() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "内容写入请求缺少用户身份");
        }
        try {
            ContentPost post = contentPostCreationPort.createIdempotent(request.userId(),
                    new ContentPostInput(request.title(), request.summary(), request.content(),
                            request.categoryId(), request.coverImage(), request.tags()),
                    request.publish() == null || request.publish(), request.requestId());
            return ResponseEntity.ok(post);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    private void authorize(String token) {
        if (token == null || !MessageDigest.isEqual(
                internalToken.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not Found");
        }
    }

    public record CreateRequest(Long userId, String title, String summary, String content,
                                String categoryId, String coverImage, List<String> tags,
                                Boolean publish, String requestId) {
    }
}
