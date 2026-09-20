package com.xander.lab.automation.api;

import com.xander.lab.automation.application.BlogPlanRunReadService;
import com.xander.lab.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/blog-plans")
@RequiredArgsConstructor
public class BlogPlanRunReadController {

    private final BlogPlanRunReadService runReadService;

    @GetMapping("/{id}/runs")
    public Result<BlogPlanRunsPage> list(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        return Result.success(runReadService.list(parseUserId(userId), id, page, size));
    }

    @GetMapping("/{id}/runs/{runId}")
    public Result<BlogPlanRunVO> detail(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable Long id,
            @PathVariable Long runId) {
        return Result.success(runReadService.detail(parseUserId(userId), id, runId));
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
