package com.xander.lab.automation.api;

import com.xander.lab.automation.application.BlogContentPlanService;
import com.xander.lab.automation.application.BlogPlanRunDispatcher;
import com.xander.lab.automation.application.BlogPlanRunService;
import com.xander.lab.automation.domain.BlogPlanRun;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Internal execution boundary for the content-automation manual trigger. */
@RestController
@RequestMapping("/internal/automation/blog-plans")
@ConditionalOnProperty(prefix = "security.state-sync", name = "internal-api-enabled",
        havingValue = "true")
public class InternalBlogPlanTriggerController {

    private static final String INTERNAL_TOKEN_HEADER = "X-DinQor-Internal-Token";

    private final BlogContentPlanService planService;
    private final BlogPlanRunDispatcher dispatcher;
    private final BlogPlanRunService runService;
    private final String internalToken;

    public InternalBlogPlanTriggerController(
            BlogContentPlanService planService,
            BlogPlanRunDispatcher dispatcher,
            BlogPlanRunService runService,
            @Value("${security.state-sync.internal-token:}") String internalToken) {
        this.planService = planService;
        this.dispatcher = dispatcher;
        this.runService = runService;
        this.internalToken = internalToken;
        if (internalToken == null || internalToken.isBlank()) {
            throw new IllegalStateException(
                    "security.state-sync.internal-token 在启用内网计划执行 API 时不能为空");
        }
    }

    @PostMapping("/{id}/trigger")
    public BlogPlanRunVO trigger(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @PathVariable Long id,
            @RequestBody UserRequest request) {
        authorize(token);
        BlogPlanRun run = planService.trigger(request.userId(), id);
        dispatcher.dispatchManual(run, request.userId());
        return runService.toRunVO(run);
    }

    private void authorize(String token) {
        if (token == null || !MessageDigest.isEqual(
                internalToken.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not Found");
        }
    }

    public record UserRequest(Long userId) {
    }
}
