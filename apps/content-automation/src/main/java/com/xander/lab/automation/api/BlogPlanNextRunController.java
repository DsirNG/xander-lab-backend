package com.xander.lab.automation.api;

import com.xander.lab.automation.application.BlogPlanNextRunService;
import com.xander.lab.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/blog-plans")
@RequiredArgsConstructor
public class BlogPlanNextRunController {

    private final BlogPlanNextRunService nextRunService;

    @GetMapping("/{id}/next")
    public Result<Instant> nextRun(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") long count) {
        return Result.success(nextRunService.nextRun(parseUserId(userId), id, count));
    }

    private Long parseUserId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
