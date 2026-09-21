package com.xander.lab.publishing.api;

import com.xander.lab.common.Result;
import com.xander.lab.content.api.ContentPostInput;
import com.xander.lab.publishing.application.ContentAgentPostService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public publishing create entry owned by content-automation during Wave 2 drain. */
@RestController
@RequestMapping("/api/blog")
@ConditionalOnProperty(prefix = "content-automation", name = "publishing-create-enabled",
        havingValue = "true")
public class BlogCreateController {

    private final ContentAgentPostService service;

    public BlogCreateController(ContentAgentPostService service) {
        this.service = service;
    }

    @PostMapping("/posts")
    public Result<BlogPostVO> create(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @RequestHeader(value = "Idempotency-Key", required = false) String requestId,
            @RequestBody BlogPostDTO request) {
        Long userId = parseUserId(userIdHeader);
        if (userId == null) {
            return Result.unauthorized("未登录或登录已过期");
        }
        if (request == null) {
            return Result.badRequest("请求体不能为空");
        }
        boolean publish = request.getPublish() == null || request.getPublish();
        ContentPostInput input = new ContentPostInput(request.getTitle(), request.getSummary(),
                request.getContent(), request.getCategoryId(), request.getCoverImage(), request.getTags());
        return Result.success(service.createBlog(userId, input, publish, requestId));
    }

    private Long parseUserId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long userId = Long.parseLong(value.trim());
            return userId > 0 ? userId : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
