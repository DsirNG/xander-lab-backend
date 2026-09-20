package com.xander.lab.automation.api;

import com.xander.lab.automation.application.BlogPlanReadService;
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
public class BlogPlanReadController {

    private final BlogPlanReadService planReadService;

    @GetMapping
    public Result<BlogContentPlansPage> list(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        return Result.success(planReadService.list(parseUserId(userId), page, size, authorization));
    }

    @GetMapping("/{id}")
    public Result<BlogContentPlanVO> detail(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id) {
        return Result.success(planReadService.detail(parseUserId(userId), id, authorization));
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
