package com.xander.lab.blogagent.api;

import com.xander.lab.blogagent.application.BlogAgentTaskWriteService;
import com.xander.lab.blogagent.application.BlogAgentPublishService;
import com.xander.lab.blogagent.domain.BlogAgentTask;
import com.xander.lab.common.Result;
import com.xander.lab.publishing.api.BlogPostVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Create and non-stream execution commands owned by content-automation. */
@RestController
@RequestMapping("/api/blog-agent/tasks")
public class BlogAgentCommandController {

    private final BlogAgentTaskWriteService service;
    private final BlogAgentPublishService publishService;

    public BlogAgentCommandController(BlogAgentTaskWriteService service,
                                      BlogAgentPublishService publishService) {
        this.service = service;
        this.publishService = publishService;
    }

    @PostMapping
    public Result<BlogAgentTask> create(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @Valid @RequestBody BlogAgentTaskCreateRequest request) {
        Long userId = parseUserId(userIdHeader);
        return userId == null
                ? Result.unauthorized("未登录或登录已过期")
                : Result.success(service.create(userId, request));
    }

    @PostMapping("/{id}/run")
    public Result<com.xander.lab.blogagent.api.BlogAgentTaskVO> run(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @PathVariable long id) {
        Long userId = parseUserId(userIdHeader);
        return userId == null
                ? Result.unauthorized("未登录或登录已过期")
                : Result.success(service.run(userId, id));
    }

    @PostMapping("/{id}/publish")
    public Result<BlogPostVO> publish(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @PathVariable long id) {
        Long userId = parseUserId(userIdHeader);
        return userId == null
                ? Result.unauthorized("未登录或登录已过期")
                : Result.success(publishService.publish(userId, id));
    }

    private static Long parseUserId(String header) {
        if (header == null || header.isBlank()) return null;
        try {
            long value = Long.parseLong(header.trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
