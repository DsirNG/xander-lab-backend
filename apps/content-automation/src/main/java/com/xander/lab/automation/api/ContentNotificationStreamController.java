package com.xander.lab.automation.api;

import com.xander.lab.automation.infrastructure.ContentNotificationStreamService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/** Content-owned user notification SSE endpoint. */
@RestController
@RequestMapping("/api/notifications")
public class ContentNotificationStreamController {

    private final ContentNotificationStreamService streamService;

    public ContentNotificationStreamController(ContentNotificationStreamService streamService) {
        this.streamService = streamService;
    }

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
                             HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        return streamService.subscribe(parseUserId(userIdHeader));
    }

    private long parseUserId(String value) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(UNAUTHORIZED, "未登录或登录已过期");
        }
        try {
            long userId = Long.parseLong(value.trim());
            if (userId > 0) return userId;
        } catch (NumberFormatException ignored) {
            // mapped below
        }
        throw new ResponseStatusException(UNAUTHORIZED, "未登录或登录已过期");
    }
}
