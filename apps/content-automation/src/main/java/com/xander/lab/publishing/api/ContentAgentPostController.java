package com.xander.lab.publishing.api;

import com.xander.lab.automation.host.ContentAutomationProperties;
import com.xander.lab.content.api.ContentPost;
import com.xander.lab.content.api.ContentPostPage;
import com.xander.lab.content.api.ContentPostPort;
import com.xander.lab.content.api.ContentPostQuery;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/** Agent-only article contract owned by Content-Automation. */
@RestController
@RequestMapping("/internal/agent/content")
@ConditionalOnProperty(prefix = "content-automation", name = "agent-content-api-enabled",
        havingValue = "true")
public class ContentAgentPostController {

    private static final String TOKEN_HEADER = "X-DinQor-Internal-Token";

    private final ContentPostPort contentPostPort;
    private final String internalToken;

    public ContentAgentPostController(ContentPostPort contentPostPort,
                                      ContentAutomationProperties properties) {
        if (properties.getAgentInternalToken() == null || properties.getAgentInternalToken().isBlank()) {
            throw new IllegalStateException(
                    "content-automation.agent-internal-token 在启用 Agent 内容 API 时不能为空");
        }
        this.contentPostPort = contentPostPort;
        this.internalToken = properties.getAgentInternalToken();
    }

    @PostMapping("/list-my-posts")
    public ContentPostPage listMyPosts(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                       @RequestBody ListMyPostsRequest request) {
        authorize(token);
        return contentPostPort.listMyPosts(request.userId(), request.query(), request.page(), request.size());
    }

    @PostMapping("/list-all-my-posts")
    public ContentPostList listAllMyPosts(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                          @RequestBody ListAllMyPostsRequest request) {
        authorize(token);
        return new ContentPostList(contentPostPort.listAllMyPosts(
                request.userId(), request.query(), request.limit()));
    }

    @PostMapping("/find-owned")
    public ContentPost findOwned(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                 @RequestBody FindOwnedRequest request) {
        authorize(token);
        return contentPostPort.findOwned(request.userId(), request.postId());
    }

    @PostMapping("/count-managed")
    public CountResponse countManaged(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                      @RequestBody UserRequest request) {
        authorize(token);
        return new CountResponse(contentPostPort.countManaged(request.userId()));
    }

    @PostMapping("/create")
    public ContentPost create(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
                              @RequestBody CreateRequest request) {
        authorize(token);
        return contentPostPort.create(request.userId(), request.input(), request.publish());
    }

    @PostMapping("/update")
    public ContentPost update(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
                              @RequestBody UpdateRequest request) {
        authorize(token);
        return contentPostPort.update(request.userId(), request.postId(), request.input());
    }

    @PostMapping("/move-to-trash")
    public void moveToTrash(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
                            @RequestBody FindOwnedRequest request) {
        authorize(token);
        contentPostPort.moveToTrash(request.userId(), request.postId());
    }

    private void authorize(String token) {
        if (token == null || !MessageDigest.isEqual(internalToken.getBytes(StandardCharsets.UTF_8),
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

    public record CreateRequest(Long userId, com.xander.lab.content.api.ContentPostInput input,
                                boolean publish) {
    }

    public record UpdateRequest(Long userId, Long postId,
                                com.xander.lab.content.api.ContentPostInput input) {
    }

    public record CountResponse(long count) {
    }

    public record ContentPostList(List<ContentPost> posts) {
    }
}
