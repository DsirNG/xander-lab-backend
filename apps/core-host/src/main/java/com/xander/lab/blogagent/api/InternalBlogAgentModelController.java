package com.xander.lab.blogagent.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.core.api.BlogAgentModelContract;
import com.xander.lab.blogagent.infrastructure.BlogAgentProperties;
import com.xander.lab.blogagent.infrastructure.BlogAgentModelClient;
import com.xander.lab.common.ModelCallResult;
import com.xander.lab.common.ModelUsage;
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

/**
 * Stateless model boundary used while BlogAgent durable task ownership drains to content-automation.
 *
 * <p>This controller deliberately accepts stage inputs and returns model results only. It does not
 * read or write BlogAgent tables, so content-automation remains the owner of task state while the
 * admin-managed model credentials stay inside core-host until the model gateway is extracted.</p>
 */
@RestController
@RequestMapping("/internal/agent/blog/model")
@ConditionalOnProperty(prefix = "security.state-sync", name = "internal-api-enabled",
        havingValue = "true")
public class InternalBlogAgentModelController {

    private static final String INTERNAL_TOKEN_HEADER = "X-DinQor-Internal-Token";

    private final BlogAgentModelClient modelClient;
    private final ObjectMapper objectMapper;
    private final boolean modelGatewayEnabled;
    private final BlogAgentProperties blogAgentProperties;
    private final String internalToken;

    public InternalBlogAgentModelController(
            BlogAgentModelClient modelClient,
            ObjectMapper objectMapper,
            BlogAgentProperties blogAgentProperties,
            @Value("${admin.model-gateway.enabled:false}") boolean modelGatewayEnabled,
            @Value("${security.state-sync.internal-token:}") String internalToken) {
        this.modelClient = modelClient;
        this.objectMapper = objectMapper;
        this.blogAgentProperties = blogAgentProperties;
        this.modelGatewayEnabled = modelGatewayEnabled;
        this.internalToken = internalToken;
        if (internalToken == null || internalToken.isBlank()) {
            throw new IllegalStateException(
                    "security.state-sync.internal-token 在启用内网博客模型 API 时不能为空");
        }
    }

    @PostMapping("/analyze")
    public BlogAgentModelContract.Response analyze(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @RequestBody BlogAgentModelContract.TextRequest request) {
        authorize(token);
        return response(modelClient.analyze(request.input()));
    }

    @PostMapping("/research")
    public BlogAgentModelContract.Response research(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @RequestBody BlogAgentModelContract.ResearchRequest request) {
        authorize(token);
        try {
            return response(modelClient.research(request.input(), objectMapper.readTree(request.analysisJson())));
        } catch (Exception exception) {
            throw new IllegalArgumentException("博客模型研究阶段参数无效", exception);
        }
    }

    @PostMapping("/article")
    public BlogAgentModelContract.Response article(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @RequestBody BlogAgentModelContract.TextRequest request) {
        authorize(token);
        return response(modelClient.createArticle(request.input()));
    }

    @PostMapping("/review")
    public BlogAgentModelContract.Response review(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @RequestBody BlogAgentModelContract.ReviewRequest request) {
        authorize(token);
        return response(modelClient.reviewArticle(request.content(), request.request()));
    }

    @PostMapping("/compliance")
    public BlogAgentModelContract.Response compliance(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @RequestBody BlogAgentModelContract.ComplianceRequest request) {
        authorize(token);
        return response(modelClient.reviewCompliance(request.title(), request.content()));
    }

    private BlogAgentModelContract.Response response(ModelCallResult result) {
        try {
            ModelUsage usage = result.usage() == null ? ModelUsage.NONE : result.usage();
            return new BlogAgentModelContract.Response(
                    objectMapper.writeValueAsString(result.payload()),
                    usage.inputTokens(), usage.cachedTokens(), usage.outputTokens(), result.model());
        } catch (Exception exception) {
            throw new IllegalStateException("博客模型结果序列化失败", exception);
        }
    }

    private void authorize(String token) {
        if (token == null || !MessageDigest.isEqual(
                internalToken.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not Found");
        }
        if (!modelGatewayEnabled
                && (blogAgentProperties.getApiKey() == null || blogAgentProperties.getApiKey().isBlank()
                || blogAgentProperties.getModel() == null || blogAgentProperties.getModel().isBlank())) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "博客生成助手尚未配置模型服务");
        }
    }

}
