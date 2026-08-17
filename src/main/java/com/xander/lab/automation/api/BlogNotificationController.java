package com.xander.lab.automation.api;

import com.xander.lab.automation.application.BlogNotificationService;
import com.xander.lab.automation.infrastructure.BlogPlanSseService;
import com.xander.lab.common.Result;
import com.xander.lab.identity.security.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class BlogNotificationController {

    private final BlogNotificationService notificationService;
    private final BlogPlanSseService sseService;

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() {
        return sseService.subscribe(UserContext.getUserId());
    }

    /** Sends a transient diagnostic message to currently connected clients only.
     *  Admin-only endpoint lives under /api/admin/notifications (see
     *  AdminNotificationController), where the identity interceptor enforces role. */
    @GetMapping
    public Result<BlogNotificationListResult> list(@RequestParam(defaultValue = "1") long page,
                                                   @RequestParam(defaultValue = "10") long size) {
        return Result.success(notificationService.list(UserContext.getUserId(), page, size));
    }

    @GetMapping("/unread-count")
    public Result<Long> unreadCount() {
        return Result.success(notificationService.unreadCount(UserContext.getUserId()));
    }

    @PatchMapping("/{id}/read")
    public Result<Void> markRead(@PathVariable Long id) {
        notificationService.markRead(UserContext.getUserId(), id);
        return Result.success();
    }

    @PatchMapping("/read-all")
    public Result<Void> markAllRead() {
        notificationService.markAllRead(UserContext.getUserId());
        return Result.success();
    }
}
