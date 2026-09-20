package com.xander.lab.automation.api;

import com.xander.lab.automation.application.BlogPlanCreateService;
import com.xander.lab.automation.application.BlogPlanReadService;
import com.xander.lab.automation.infrastructure.RecitationMaterialClient;
import com.xander.lab.common.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Content-automation owner for scheduled plan creation. */
@RestController
@RequestMapping("/api/blog-plans")
@RequiredArgsConstructor
public class BlogPlanCreateController {

    private final BlogPlanCreateService createService;
    private final BlogPlanReadService readService;
    private final RecitationMaterialClient materialClient;

    @PostMapping
    public Result<BlogContentPlanVO> create(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody BlogContentPlanCreateRequest request) {
        Long parsedUserId = parseUserId(userId);
        materialClient.requireOwnedMaterial(request.getKnowledgeMaterialId(), authorization);
        Long planId = createService.create(parsedUserId, request);
        return Result.success(readService.detail(parsedUserId, planId, authorization));
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
