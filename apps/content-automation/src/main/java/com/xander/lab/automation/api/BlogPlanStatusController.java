package com.xander.lab.automation.api;

import com.xander.lab.automation.application.BlogPlanReadService;
import com.xander.lab.automation.application.BlogPlanStatusService;
import com.xander.lab.common.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/blog-plans")
@RequiredArgsConstructor
public class BlogPlanStatusController {

    private final BlogPlanStatusService statusService;
    private final BlogPlanReadService planReadService;

    @PatchMapping("/{id}/status")
    public Result<BlogContentPlanVO> updateStatus(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id,
            @Valid @RequestBody BlogContentPlanStatusRequest request) {
        statusService.update(parseUserId(userId), id, request.getAction());
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
