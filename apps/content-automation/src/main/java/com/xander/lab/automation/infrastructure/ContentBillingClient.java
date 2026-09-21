package com.xander.lab.automation.infrastructure;

import com.xander.lab.automation.host.ContentAutomationProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.time.Duration;

/** Calls Core's narrow automation billing boundary; it cannot access the ledger directly. */
@Component
public class ContentBillingClient {

    private static final String INTERNAL_TOKEN_HEADER = "X-DinQor-Internal-Token";

    private final RestClient restClient;
    private final ContentAutomationProperties properties;

    @Autowired
    public ContentBillingClient(RestClient.Builder restClientBuilder,
                                ContentAutomationProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(timeout(
                properties.getCoreConnectTimeoutMillis())));
        requestFactory.setReadTimeout(Duration.ofMillis(timeout(
                properties.getCoreReadTimeoutMillis())));
        this.restClient = restClientBuilder.requestFactory(requestFactory).build();
        this.properties = properties;
    }

    ContentBillingClient(RestClient restClient, ContentAutomationProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    public long flatPoints(String bizType) {
        PointsResponse response = post("/internal/automation/billing/flat-points",
                new BizTypeRequest(bizType), PointsResponse.class);
        return response == null ? 0L : response.points();
    }

    public void chargeFlat(Long userId, String bizType, String bizKey, String bizRef,
                           long points, String remark) {
        post("/internal/automation/billing/charge-flat",
                new ChargeFlatRequest(userId, bizType, bizKey, bizRef, points, remark), Void.class);
    }

    private <T> T post(String path, Object request, Class<T> responseType) {
        String baseUrl = requireBaseUrl();
        String token = requireToken();
        try {
            return restClient.post()
                    .uri(URI.create(baseUrl + path))
                    .header(INTERNAL_TOKEN_HEADER, token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(responseType);
        } catch (RestClientException exception) {
            throw new IllegalStateException("Core 计费边界不可用", exception);
        }
    }

    private String requireBaseUrl() {
        String value = properties.getCoreBaseUrl();
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("content-automation.core-base-url 未配置");
        }
        return value.replaceAll("/+$", "");
    }

    private String requireToken() {
        String value = properties.getCoreInternalToken();
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("content-automation.core-internal-token 未配置");
        }
        return value;
    }

    private long timeout(int value) {
        return Math.max(1, value);
    }

    public record BizTypeRequest(String bizType) {
    }

    public record ChargeFlatRequest(Long userId, String bizType, String bizKey, String bizRef,
                                    long points, String remark) {
    }

    public record PointsResponse(long points) {
    }
}
