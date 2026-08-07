package com.xander.lab.publishing.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.xander.lab.publishing.domain.BlogPublisher;
import com.xander.lab.publishing.domain.PublishCommand;
import com.xander.lab.publishing.domain.PublishResult;
import com.xander.lab.publishing.application.CsdnCredentialService;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.UUID;
import java.util.stream.Collectors;

/** Publishes an article through CSDN's web console API. */
@Component
public class CsdnPublisher implements BlogPublisher {
    private final CsdnProperties properties;
    private final CsdnCredentialService credentials;
    private final RestClient client;

    public CsdnPublisher(CsdnProperties properties, CsdnCredentialService credentials) {
        this.properties = properties;
        this.credentials = credentials;
        this.client = RestClient.builder().baseUrl(properties.getBaseUrl()).build();
    }

    @Override public String platform() { return "csdn"; }

    @Override
    public PublishResult publish(PublishCommand command) {
        if (!properties.isEnabled()) throw new IllegalStateException("CSDN publisher is disabled");
        String cookie = credentials.requireCookie(command.userId());
        String nonce = UUID.randomUUID().toString();
        JsonNode response = client.post().uri("/blog-console-api/v1/postedit/saveArticle")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", cookie)
                .header("origin", "https://mp.csdn.net")
                .header("referer", "https://mp.csdn.net/")
                .header("x-ca-key", properties.getApiKey())
                .header("x-ca-nonce", nonce)
                .header("x-ca-signature-headers", "x-ca-key,x-ca-nonce")
                .header("x-ca-signature", properties.getSignature() == null ? "" : properties.getSignature())
                .body(java.util.Map.of("title", command.title(), "description", command.summary() == null ? "" : command.summary(),
                        "content", command.content(), "tags", command.tags().stream().collect(Collectors.joining(",")),
                        "categories", command.categoryId() == null ? "" : command.categoryId(), "status", 1, "type", "original", "read_type", "public", "source", "pc_mdeditor"))
                .retrieve().body(JsonNode.class);
        if (response == null || response.path("code").asInt(0) != 200) {
            throw new IllegalStateException("CSDN publish failed: " + (response == null ? "empty response" : response.path("msg").asText("unknown error")));
        }
        JsonNode data = response.path("data");
        return new PublishResult(platform(), data.path("article_id").asText(), data.path("url").asText());
    }
}
