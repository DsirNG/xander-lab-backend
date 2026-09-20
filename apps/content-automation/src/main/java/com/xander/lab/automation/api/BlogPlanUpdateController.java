package com.xander.lab.automation.api;

import com.xander.lab.automation.application.BlogPlanReadService;
import com.xander.lab.automation.application.BlogPlanUpdateService;
import com.xander.lab.automation.infrastructure.RecitationMaterialClient;
import com.xander.lab.common.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Content-automation owner for scheduled plan updates. */
@RestController
@RequestMapping("/api/blog-plans")
@RequiredArgsConstructor
public class BlogPlanUpdateController {

    private final BlogPlanUpdateService updateService;
    private final BlogPlanReadService readService;
    private final RecitationMaterialClient materialClient;

    @PatchMapping("/{id}")
    public Result<BlogContentPlanVO> update(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id,
            @Valid @RequestBody BlogContentPlanUpdateRequest request) {
        Long parsedUserId = parseUserId(userId);
        materialClient.requireOwnedMaterial(request.getKnowledgeMaterialId(), authorization);
        updateService.update(parsedUserId, id, request);
        return Result.success(readService.detail(parsedUserId, id, authorization));
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
