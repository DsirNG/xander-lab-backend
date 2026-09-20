package com.xander.lab.identity.api;

import com.xander.lab.content.api.ContentPost;
import com.xander.lab.content.api.ContentPostInput;
import com.xander.lab.content.api.ContentPostPage;
import com.xander.lab.content.api.ContentPostPort;
import com.xander.lab.content.api.ContentPostQuery;
import com.xander.lab.identity.application.SecurityStateSyncProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/** Agent 专用的内容文章契约 API；默认关闭，不暴露内容域管理接口。 */
@RestController
@RequestMapping("/internal/agent/content")
@ConditionalOnProperty(prefix = "security.state-sync", name = "internal-api-enabled",
        havingValue = "true")
public class AgentContentPostController {
    private static final String INTERNAL_TOKEN_HEADER = "X-DinQor-Internal-Token";

    private final ContentPostPort contentPostPort;
    private final SecurityStateSyncProperties properties;

    public AgentContentPostController(ContentPostPort contentPostPort,
                                      SecurityStateSyncProperties properties) {
        this.contentPostPort = contentPostPort;
        this.properties = properties;
        if (properties.getInternalToken() == null || properties.getInternalToken().isBlank()) {
            throw new IllegalStateException(
                    "security.state-sync.internal-token 在启用内网内容 API 时不能为空");
        }
    }

    @PostMapping("/list-my-posts")
    public ContentPostPage listMyPosts(@RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
                                       @RequestBody ListMyPostsRequest request) {
        authorize(token);
        return contentPostPort.listMyPosts(request.userId(), request.query(), request.page(), request.size());
    }

    @PostMapping("/list-all-my-posts")
    public ContentPostList listAllMyPosts(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @RequestBody ListAllMyPostsRequest request) {
        authorize(token);
        return new ContentPostList(contentPostPort.listAllMyPosts(
                request.userId(), request.query(), request.limit()));
    }

    @PostMapping("/find-owned")
    public ContentPost findOwned(@RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
                                 @RequestBody FindOwnedRequest request) {
        authorize(token);
        return contentPostPort.findOwned(request.userId(), request.postId());
    }

    @PostMapping("/count-managed")
    public CountResponse countManaged(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @RequestBody UserRequest request) {
        authorize(token);
        return new CountResponse(contentPostPort.countManaged(request.userId()));
    }

    @PostMapping("/create")
    public ContentPost create(@RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
                              @RequestBody CreateRequest request) {
        authorize(token);
        return contentPostPort.create(request.userId(), request.input(), request.publish());
    }

    @PostMapping("/update")
    public ContentPost update(@RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
                              @RequestBody UpdateRequest request) {
        authorize(token);
        return contentPostPort.update(request.userId(), request.postId(), request.input());
    }

    @PostMapping("/move-to-trash")
    public void moveToTrash(@RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
                            @RequestBody FindOwnedRequest request) {
        authorize(token);
        contentPostPort.moveToTrash(request.userId(), request.postId());
    }

    private void authorize(String token) {
        if (token == null || !MessageDigest.isEqual(
                properties.getInternalToken().getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not Found");
        }
    }

    public record ListMyPostsRequest(Long userId, ContentPostQuery query, int page, int size) {
    }

    public record ListAllMyPostsRequest(Long userId, ContentPostQuery query, Integer limit) {
    }

    public record FindOwnedRequest(Long userId, Long postId) {
    }

    public record UserRequest(Long userId) {
    }

    public record CreateRequest(Long userId, ContentPostInput input, boolean publish) {
    }

    public record UpdateRequest(Long userId, Long postId, ContentPostInput input) {
    }

    public record CountResponse(long count) {
    }

    public record ContentPostList(List<ContentPost> posts) {
    }
}
