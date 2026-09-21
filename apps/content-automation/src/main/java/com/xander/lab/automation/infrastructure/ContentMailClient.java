package com.xander.lab.automation.infrastructure;

import com.xander.lab.automation.host.ContentAutomationProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.time.Duration;

/** Calls Core's token-protected, already-rendered mail delivery boundary. */
@Component
public class ContentMailClient {

    private static final String INTERNAL_TOKEN_HEADER = "X-DinQor-Internal-Token";

    private final RestClient restClient;
    private final ContentAutomationProperties properties;

    public ContentMailClient(RestClient.Builder restClientBuilder,
                             ContentAutomationProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(timeout(
                properties.getCoreConnectTimeoutMillis())));
        requestFactory.setReadTimeout(Duration.ofMillis(timeout(
                properties.getCoreReadTimeoutMillis())));
        this.restClient = restClientBuilder.requestFactory(requestFactory).build();
        this.properties = properties;
    }

    ContentMailClient(RestClient restClient, ContentAutomationProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    public void sendSimpleMail(String to, String subject, String content) {
        post("/internal/automation/mail/simple", new SimpleMailRequest(to, subject, content));
    }

    public void sendHtmlMail(String to, String subject, String plainText, String html) {
        post("/internal/automation/mail/html", new HtmlMailRequest(to, subject, plainText, html));
    }

    private void post(String path, Object request) {
        String baseUrl = requireBaseUrl();
        String token = requireToken();
        try {
            restClient.post()
                    .uri(URI.create(baseUrl + path))
                    .header(INTERNAL_TOKEN_HEADER, token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException exception) {
            throw new IllegalStateException("Core 邮件投递边界不可用", exception);
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

    public record SimpleMailRequest(String to, String subject, String content) {
    }

    public record HtmlMailRequest(String to, String subject, String plainText, String html) {
    }
}
