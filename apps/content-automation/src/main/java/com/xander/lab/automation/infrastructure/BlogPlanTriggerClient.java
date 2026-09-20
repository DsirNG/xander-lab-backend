package com.xander.lab.automation.infrastructure;

import com.xander.lab.automation.api.BlogPlanRunVO;
import com.xander.lab.automation.host.ContentAutomationProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;

/** Calls the core-host execution boundary for manual plan runs. */
@Component
@RequiredArgsConstructor
public class BlogPlanTriggerClient {

    private static final String INTERNAL_TOKEN_HEADER = "X-DinQor-Internal-Token";

    private final RestClient.Builder restClientBuilder;
    private final ContentAutomationProperties properties;

    public BlogPlanRunVO trigger(Long userId, Long planId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
        }
        String baseUrl = properties.getCoreBaseUrl();
        String internalToken = properties.getCoreInternalToken();
        if (baseUrl == null || baseUrl.isBlank() || internalToken == null || internalToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "计划执行服务尚未配置内网凭证");
        }
        try {
            return restClientBuilder.build()
                    .post()
                    .uri(URI.create(baseUrl.replaceAll("/+$", "")
                            + "/internal/automation/blog-plans/" + planId + "/trigger"))
                    .header(INTERNAL_TOKEN_HEADER, internalToken)
                    .body(new UserRequest(userId))
                    .retrieve()
                    .body(BlogPlanRunVO.class);
        } catch (RestClientException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "计划执行服务暂时无法连接", exception);
        }
    }

    private record UserRequest(Long userId) {
    }
}
