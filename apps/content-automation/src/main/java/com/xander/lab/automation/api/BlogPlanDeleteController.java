package com.xander.lab.automation.api;

import com.xander.lab.automation.application.BlogPlanDeleteService;
import com.xander.lab.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/blog-plans")
@RequiredArgsConstructor
public class BlogPlanDeleteController {

    private final BlogPlanDeleteService deleteService;

    @DeleteMapping("/{id}")
    public Result<Void> delete(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable Long id) {
        deleteService.delete(parseUserId(userId), id);
        return Result.success();
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
