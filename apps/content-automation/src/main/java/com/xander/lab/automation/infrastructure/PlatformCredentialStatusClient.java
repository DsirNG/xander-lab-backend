package com.xander.lab.automation.infrastructure;

import com.xander.lab.automation.host.ContentAutomationProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Reads integration-owned authorization state without taking ownership of credential tables. */
@Slf4j
@Component
public class PlatformCredentialStatusClient {

    private final RestClient restClient;
    private final ContentAutomationProperties properties;

    public PlatformCredentialStatusClient(RestClient.Builder restClientBuilder,
                                          ContentAutomationProperties properties) {
        this.restClient = restClientBuilder.build();
        this.properties = properties;
    }

    public boolean isAuthorized(String platform, String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return false;
        }
        String baseUrl = properties.getPlatformIntegrationBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        try {
            JsonNode body = restClient.get()
                    .uri(baseUrl.replaceAll("/+$", "")
                            + "/api/publishing/" + platform + "/authorization/status")
                    .header(HttpHeaders.AUTHORIZATION, authorization)
                    .retrieve()
                    .body(JsonNode.class);
            return body != null && "AUTHORIZED".equals(body.path("data").path("status").asText());
        } catch (RuntimeException exception) {
            log.warn("[BlogPlan] credential status fail-soft platform={} reason={}",
                    platform, rootMessage(exception));
            return false;
        }
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
