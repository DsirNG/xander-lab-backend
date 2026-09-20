package com.xander.lab.automation.api;

import com.xander.lab.automation.infrastructure.BlogAgentTopicSeriesClient;
import com.xander.lab.common.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;

/** Content-automation owner for AI topic-series generation. */
@RestController
@RequestMapping("/api/blog-plans/topics")
@RequiredArgsConstructor
public class BlogPlanTopicsController {

    private final BlogAgentTopicSeriesClient topicSeriesClient;

    @PostMapping("/generate")
    public Result<List<String>> generate(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @Valid @RequestBody BlogContentPlanTopicsGenerateRequest request) {
        Long parsedUserId = parseUserId(userId);
        List<String> excludeTitles = request.getExcludeTitles() == null
                ? new ArrayList<>() : request.getExcludeTitles();
        return Result.success(topicSeriesClient.generate(parsedUserId, request.getTopic().strip(),
                request.getAudience(), request.getTone(), request.getCount(), excludeTitles));
    }

    private Long parseUserId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
        }
    }
}
