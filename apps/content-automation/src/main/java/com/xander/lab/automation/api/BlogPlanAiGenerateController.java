package com.xander.lab.automation.api;

import com.xander.lab.automation.application.BlogPlanAiGenerateService;
import com.xander.lab.automation.application.BlogPlanReadService;
import com.xander.lab.common.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Content-automation owner for AI-generated one-shot plan batches. */
@RestController
@RequestMapping("/api/blog-plans")
@RequiredArgsConstructor
public class BlogPlanAiGenerateController {

    private final BlogPlanAiGenerateService generateService;
    private final BlogPlanReadService readService;

    @PostMapping("/ai-generate")
    public Result<List<BlogContentPlanVO>> generate(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody BlogContentPlanAiGenerateRequest request) {
        Long parsedUserId = parseUserId(userId);
        List<Long> ids = generateService.generate(parsedUserId, request);
        return Result.success(ids.stream()
                .map(id -> readService.detail(parsedUserId, id, authorization))
                .toList());
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
