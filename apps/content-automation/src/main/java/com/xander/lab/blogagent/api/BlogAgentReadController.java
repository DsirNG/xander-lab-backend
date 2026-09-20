package com.xander.lab.blogagent.api;

import com.xander.lab.blogagent.application.BlogAgentTaskReadService;
import com.xander.lab.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** HTTP read facade for the BlogAgent task/session surface owned by content-automation. */
@RestController
@RequestMapping("/api/blog-agent/tasks")
public class BlogAgentReadController {

    private static final String UNAUTHORIZED_MESSAGE = "未登录或登录已过期";

    private final BlogAgentTaskReadService service;

    public BlogAgentReadController(BlogAgentTaskReadService service) {
        this.service = service;
    }

    @GetMapping
    public Result<List<BlogAgentSessionVO>> list(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader) {
        Long userId = parseUserId(userIdHeader);
        return userId == null
                ? Result.unauthorized(UNAUTHORIZED_MESSAGE)
                : Result.success(service.listSessions(userId));
    }

    @GetMapping("/{id}")
    public Result<BlogAgentTaskVO> detail(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @PathVariable long id) {
        Long userId = parseUserId(userIdHeader);
        return userId == null
                ? Result.unauthorized(UNAUTHORIZED_MESSAGE)
                : Result.success(service.detail(userId, id));
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
