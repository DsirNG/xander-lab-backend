package com.xander.lab.blogagent.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.xander.lab.automation.host.ContentAutomationProperties;
import com.xander.lab.core.api.BlogAgentModelContract;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;

/** Calls the core-host stateless model boundary without importing its BlogAgent implementation. */
@Component
@RequiredArgsConstructor
public class BlogAgentModelClient {

    private static final String INTERNAL_TOKEN_HEADER = "X-DinQor-Internal-Token";

    private final RestClient.Builder restClientBuilder;
    private final ContentAutomationProperties properties;

    public BlogAgentModelContract.Response analyze(String input) {
        return post("/analyze", new BlogAgentModelContract.TextRequest(input));
    }

    public BlogAgentModelContract.Response research(String input, JsonNode analysis) {
        return post("/research",
                new BlogAgentModelContract.ResearchRequest(input, analysis.toString()));
    }

    public BlogAgentModelContract.Response article(String input) {
        return post("/article", new BlogAgentModelContract.TextRequest(input));
    }

    public BlogAgentModelContract.Response review(String content, String request) {
        return post("/review", new BlogAgentModelContract.ReviewRequest(content, request));
    }

    public BlogAgentModelContract.Response revise(String content, String instruction) {
        return post("/revise", new BlogAgentModelContract.RevisionRequest(content, instruction));
    }

    public BlogAgentModelContract.Response compliance(String title, String content) {
        return post("/compliance",
                new BlogAgentModelContract.ComplianceRequest(title, content));
    }

    private BlogAgentModelContract.Response post(String path, Object body) {
        String baseUrl = properties.getCoreBaseUrl();
        String internalToken = properties.getCoreInternalToken();
        if (baseUrl == null || baseUrl.isBlank() || internalToken == null || internalToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "博客模型服务尚未配置内网凭证");
        }
        try {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            int timeout = Math.max(1000, properties.getModelTimeoutMillis());
            requestFactory.setConnectTimeout(timeout);
            requestFactory.setReadTimeout(timeout);
            return restClientBuilder.requestFactory(requestFactory).build()
                    .post()
                    .uri(URI.create(baseUrl.replaceAll("/+$", "") + "/internal/agent/blog/model" + path))
                    .header(INTERNAL_TOKEN_HEADER, internalToken)
                    .body(body)
                    .retrieve()
                    .body(BlogAgentModelContract.Response.class);
        } catch (RestClientException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "博客模型服务暂时无法连接", exception);
        }
    }
}
