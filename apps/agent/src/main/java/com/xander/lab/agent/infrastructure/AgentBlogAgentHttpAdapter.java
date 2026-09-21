package com.xander.lab.agent.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.blogagent.spi.BlogAgentPort;
import com.xander.lab.blogagent.spi.BlogAgentPostView;
import com.xander.lab.blogagent.spi.BlogAgentTaskDraft;
import com.xander.lab.blogagent.spi.BlogAgentTaskView;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/** Agent's HTTP adapter for the Content-owned BlogAgent boundary. */
@Component
@ConditionalOnProperty(prefix = "agent.content-client", name = "enabled", havingValue = "true")
public class AgentBlogAgentHttpAdapter implements BlogAgentPort {

    private static final String TOKEN_HEADER = "X-DinQor-Internal-Token";

    private final RestClient restClient;
    private final AgentContentClientProperties properties;
    private final ObjectMapper objectMapper;

    public AgentBlogAgentHttpAdapter(RestClient.Builder builder,
                                     AgentContentClientProperties properties,
                                     ObjectMapper objectMapper) {
        if (properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()) {
            throw new IllegalStateException("agent.content-client.base-url 在启用 content-client 时不能为空");
        }
        if (properties.getInternalToken() == null || properties.getInternalToken().isBlank()) {
            throw new IllegalStateException(
                    "agent.content-client.internal-token 在启用 content-client 时不能为空");
        }
        String baseUrl = trimTrailingSlash(properties.getBaseUrl());
        this.restClient = builder.baseUrl(baseUrl).build();
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public BlogAgentTaskView create(Long userId, BlogAgentTaskDraft draft) {
        JsonNode response = postJson("/internal/agent/blogagent/create",
                new CreateRequest(userId, draft == null ? null : draft.input(),
                        draft == null ? null : draft.clientRequestId()));
        return toTaskView(response);
    }

    @Override
    public BlogAgentTaskView runStream(Long userId, Long taskId,
                                       BiConsumer<String, String> onEvent,
                                       BooleanSupplier cancellationRequested) {
        checkCancelled(cancellationRequested);
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(trimTrailingSlash(properties.getBaseUrl())
                    + "/internal/agent/blogagent/" + taskId + "/run/stream").toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty(TOKEN_HEADER, properties.getInternalToken());
            connection.setRequestProperty("Content-Type", MediaType.APPLICATION_JSON_VALUE);
            connection.setRequestProperty("Accept", MediaType.TEXT_EVENT_STREAM_VALUE);
            connection.setConnectTimeout(Math.max(1, properties.getConnectTimeoutMillis()));
            connection.setReadTimeout(Math.max(1, properties.getReadTimeoutMillis()));
            connection.setDoOutput(true);
            connection.getOutputStream().write(objectMapper.writeValueAsBytes(new UserRequest(userId, null, null)));
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("content BlogAgent boundary unavailable (HTTP " + status + ")");
            }
            return readSse(connection, onEvent, cancellationRequested);
        } catch (CancellationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("content BlogAgent boundary unavailable", exception);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    @Override
    public BlogAgentTaskView findReadyTaskByClientRequestId(Long userId, String clientRequestId) {
        return toTaskView(postJson("/internal/agent/blogagent/find-ready",
                new UserRequest(userId, null, clientRequestId)));
    }

    @Override
    public BlogAgentPostView findPublishedPost(Long userId, Long taskId) {
        return toPostView(postJson("/internal/agent/blogagent/find-published",
                new UserRequest(userId, taskId, null)));
    }

    @Override
    public BlogAgentPostView publish(Long userId, Long taskId) {
        return toPostView(postJson("/internal/agent/blogagent/" + taskId + "/publish",
                new UserRequest(userId, null, null)));
    }

    private JsonNode postJson(String path, Object body) {
        try {
            return restClient.post()
                    .uri(path)
                    .header(TOKEN_HEADER, properties.getInternalToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException exception) {
            throw new IllegalStateException("content BlogAgent boundary unavailable", exception);
        }
    }

    private BlogAgentTaskView toTaskView(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return new BlogAgentTaskView(longValue(node, "id"), text(node, "title"),
                text(node, "summary"), longValue(node, "publishedPostId"));
    }

    private BlogAgentPostView toPostView(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return new BlogAgentPostView(longValue(node, "id"), text(node, "title"),
                intValue(node, "status"), booleanValue(node, "csdnSynced"),
                booleanValue(node, "juejinSynced"));
    }

    private BlogAgentTaskView readSse(HttpURLConnection connection,
                                      BiConsumer<String, String> onEvent,
                                      BooleanSupplier cancellationRequested) throws IOException {
        String event = null;
        StringBuilder data = new StringBuilder();
        int bytes = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                checkCancelled(cancellationRequested);
                bytes += line.length() + 1;
                if (bytes > Math.max(1, properties.getMaxResponseBytes())) {
                    throw new IOException("content BlogAgent SSE response exceeds the configured limit");
                }
                if (line.startsWith("event:")) {
                    event = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:")) {
                    if (data.length() > 0) data.append('\n');
                    data.append(line.substring("data:".length()).trim());
                } else if (line.isEmpty() && data.length() > 0) {
                    String payload = data.toString();
                    if ("error".equals(event)) {
                        throw new IllegalStateException(payload);
                    }
                    if (!"heartbeat".equals(event) && onEvent != null) {
                        onEvent.accept(event == null ? "message" : event, payload);
                    }
                    if ("complete".equals(event)) {
                        return toTaskView(objectMapper.readTree(payload).path("task"));
                    }
                    event = null;
                    data.setLength(0);
                }
            }
        }
        throw new IllegalStateException("content BlogAgent stream ended before completion");
    }

    private void checkCancelled(BooleanSupplier cancellationRequested) {
        if (cancellationRequested != null && cancellationRequested.getAsBoolean()) {
            throw new CancellationException("博客生成助手执行已取消");
        }
    }

    private Long longValue(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asLong() : null;
    }

    private Integer intValue(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asInt() : null;
    }

    private Boolean booleanValue(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asBoolean() : null;
    }

    private String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private record CreateRequest(Long userId, String input, String clientRequestId) {
    }

    private record UserRequest(Long userId, Long taskId, String clientRequestId) {
    }
}
