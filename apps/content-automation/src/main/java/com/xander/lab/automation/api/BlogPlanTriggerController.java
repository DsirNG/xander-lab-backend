package com.xander.lab.automation.api;

import com.xander.lab.automation.infrastructure.BlogPlanTriggerClient;
import com.xander.lab.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Content-automation owner for manual plan triggers. */
@RestController
@RequestMapping("/api/blog-plans")
@RequiredArgsConstructor
public class BlogPlanTriggerController {

    private final BlogPlanTriggerClient triggerClient;

    @PostMapping("/{id}/trigger")
    public Result<BlogPlanRunVO> trigger(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable Long id) {
        return Result.success(triggerClient.trigger(parseUserId(userId), id));
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
