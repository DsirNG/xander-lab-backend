package com.xander.lab.publishing.api;

import com.xander.lab.common.PageData;
import com.xander.lab.common.Result;
import com.xander.lab.publishing.application.BlogMediaReadService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Private media reads extracted into content-automation. */
@RestController
@RequestMapping("/api/blog/media")
public class BlogMediaReadController {

    private final BlogMediaReadService mediaReadService;

    public BlogMediaReadController(BlogMediaReadService mediaReadService) {
        this.mediaReadService = mediaReadService;
    }

    @GetMapping("/images")
    public Result<List<BlogMediaAssetVO>> getImages(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @RequestParam(defaultValue = "recent") String scope,
            @RequestParam(required = false) String keyword) {
        Long userId = parseUserId(userIdHeader);
        if (userId == null) {
            return Result.unauthorized("未登录或登录已过期");
        }
        return Result.success(mediaReadService.images(userId, scope, keyword));
    }

    @GetMapping("/images/agent")
    public Result<PageData<AgentImageVO>> getAgentImages(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "24") int size) {
        Long userId = parseUserId(userIdHeader);
        if (userId == null) {
            return Result.unauthorized("未登录或登录已过期");
        }
        return Result.success(mediaReadService.agentImages(userId, page, size));
    }

    private Long parseUserId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
