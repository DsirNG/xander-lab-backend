package com.xander.lab.automation.api;

import com.xander.lab.automation.application.BlogNotificationReadService;
import com.xander.lab.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated notification list and acknowledgement endpoints. */
@RestController
@RequestMapping("/api/notifications")
public class BlogNotificationReadController {

    private final BlogNotificationReadService service;

    public BlogNotificationReadController(BlogNotificationReadService service) {
        this.service = service;
    }

    @GetMapping
    public Result<BlogNotificationListResult> list(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        Long userId = parseUserId(userIdHeader);
        return userId == null
                ? Result.unauthorized("未登录或登录已过期")
                : Result.success(service.list(userId, page, size));
    }

    @GetMapping("/unread-count")
    public Result<Long> unreadCount(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader) {
        Long userId = parseUserId(userIdHeader);
        return userId == null
                ? Result.unauthorized("未登录或登录已过期")
                : Result.success(service.unreadCount(userId));
    }

    @PatchMapping("/{id}/read")
    public Result<Void> markRead(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @PathVariable long id) {
        Long userId = parseUserId(userIdHeader);
        if (userId == null) {
            return Result.unauthorized("未登录或登录已过期");
        }
        return service.markRead(userId, id)
                ? Result.success()
                : Result.notFound("通知不存在");
    }

    @PatchMapping("/read-all")
    public Result<Void> markAllRead(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader) {
        Long userId = parseUserId(userIdHeader);
        if (userId == null) {
            return Result.unauthorized("未登录或登录已过期");
        }
        service.markAllRead(userId);
        return Result.success();
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
}
