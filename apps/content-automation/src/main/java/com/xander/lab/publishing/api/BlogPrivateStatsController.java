package com.xander.lab.publishing.api;

import com.xander.lab.common.Result;
import com.xander.lab.publishing.application.BlogPrivateStatsReadService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated publishing status and dashboard reads extracted into content-automation. */
@RestController
@RequestMapping("/api/blog/posts")
public class BlogPrivateStatsController {

    private final BlogPrivateStatsReadService service;

    public BlogPrivateStatsController(BlogPrivateStatsReadService service) {
        this.service = service;
    }

    @GetMapping("/publish-status")
    public Result<PublishStatus> publishStatus(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @RequestParam String requestId) {
        Long userId = parseUserId(userIdHeader);
        if (userId == null) {
            return Result.unauthorized("未登录或登录已过期");
        }
        Long postId = service.publishedPostId(userId, requestId);
        return Result.success(new PublishStatus(postId == null ? "pending" : "published", postId));
    }

    @GetMapping("/publish-rhythm")
    public Result<BlogPublishRhythmVO> publishRhythm(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader) {
        Long userId = parseUserId(userIdHeader);
        return userId == null
                ? Result.unauthorized("未登录或登录已过期")
                : Result.success(service.weeklyPublishRhythm(userId, null));
    }

    @GetMapping("/manage-stats")
    public Result<BlogManageStatsVO> manageStats(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader) {
        Long userId = parseUserId(userIdHeader);
        return userId == null
                ? Result.unauthorized("未登录或登录已过期")
                : Result.success(service.manageStats(userId, null));
    }

    private static Long parseUserId(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            long userId = Long.parseLong(header.trim());
            return userId > 0 ? userId : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public record PublishStatus(String status, Long postId) {
    }
}
