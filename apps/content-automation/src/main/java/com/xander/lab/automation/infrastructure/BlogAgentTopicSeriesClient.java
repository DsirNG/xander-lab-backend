package com.xander.lab.automation.infrastructure;

import com.xander.lab.automation.host.ContentAutomationProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.List;

/** Calls the core-host model boundary without coupling content-automation to BlogAgentService. */
@Component
@RequiredArgsConstructor
public class BlogAgentTopicSeriesClient {

    private static final String INTERNAL_TOKEN_HEADER = "X-DinQor-Internal-Token";

    private final RestClient.Builder restClientBuilder;
    private final ContentAutomationProperties properties;

    public List<String> generate(Long userId, String seed, String audience, String tone,
                                 int count, List<String> excludeTitles) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
        }
        String baseUrl = properties.getCoreBaseUrl();
        String internalToken = properties.getCoreInternalToken();
        if (baseUrl == null || baseUrl.isBlank() || internalToken == null || internalToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "博客选题服务尚未配置内网凭证");
        }
        try {
            TopicSeriesResponse response = restClientBuilder.build()
                    .post()
                    .uri(URI.create(baseUrl.replaceAll("/+$", "")
                            + "/internal/agent/blog/topic-series"))
                    .header(INTERNAL_TOKEN_HEADER, internalToken)
                    .body(new TopicSeriesRequest(userId, seed, audience, tone, count, excludeTitles))
                    .retrieve()
                    .body(TopicSeriesResponse.class);
            return response == null || response.topics() == null ? List.of() : response.topics();
        } catch (RestClientException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "博客选题服务暂时无法连接", exception);
        }
    }

    private record TopicSeriesRequest(Long userId, String seed, String audience, String tone,
                                      int count, List<String> excludeTitles) {
    }

    private record TopicSeriesResponse(List<String> topics) {
    }
}
