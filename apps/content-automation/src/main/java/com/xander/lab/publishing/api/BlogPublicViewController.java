package com.xander.lab.publishing.api;

import com.xander.lab.common.Result;
import com.xander.lab.publishing.application.BlogPublicViewService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public blog view write owned by content-automation. */
@RestController
@RequestMapping("/api/blog/posts")
public class BlogPublicViewController {

    private final BlogPublicViewService viewService;

    public BlogPublicViewController(BlogPublicViewService viewService) {
        this.viewService = viewService;
    }

    @PostMapping("/{id}/view")
    public Result<ViewResult> recordView(
            @PathVariable long id,
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @RequestHeader(value = "User-Agent", required = false) String userAgent,
            HttpServletRequest request) {
        Long userId = parseUserId(userIdHeader);
        String ip = firstClientIp(request);
        return Result.success(new ViewResult(viewService.recordView(id, userId, ip, userAgent)));
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

    private String firstClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",", 2)[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }

    public record ViewResult(boolean counted) {
    }
}
