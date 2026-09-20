package com.xander.lab.blogagent.api;

import com.xander.lab.blogagent.application.BlogAgentService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/** Internal model boundary used by content-automation plan generation. */
@RestController
@RequestMapping("/internal/agent/blog")
@ConditionalOnProperty(prefix = "security.state-sync", name = "internal-api-enabled",
        havingValue = "true")
public class AgentBlogTopicSeriesController {

    private static final String INTERNAL_TOKEN_HEADER = "X-DinQor-Internal-Token";

    private final BlogAgentService blogAgentService;
    private final String internalToken;

    public AgentBlogTopicSeriesController(BlogAgentService blogAgentService,
                                          @Value("${security.state-sync.internal-token:}") String internalToken) {
        this.blogAgentService = blogAgentService;
        this.internalToken = internalToken;
        if (internalToken == null || internalToken.isBlank()) {
            throw new IllegalStateException(
                    "security.state-sync.internal-token 在启用内网博客选题 API 时不能为空");
        }
    }

    @PostMapping("/topic-series")
    public TopicSeriesResponse generate(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @RequestBody TopicSeriesRequest request) {
        authorize(token);
        return new TopicSeriesResponse(blogAgentService.generateTopicSeries(
                request.userId(), request.seed(), request.audience(), request.tone(),
                request.count(), request.excludeTitles()));
    }

    private void authorize(String token) {
        if (token == null || !MessageDigest.isEqual(
                internalToken.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not Found");
        }
    }

    public record TopicSeriesRequest(Long userId, String seed, String audience, String tone,
                                     int count, List<String> excludeTitles) {
    }

    public record TopicSeriesResponse(List<String> topics) {
    }
}
