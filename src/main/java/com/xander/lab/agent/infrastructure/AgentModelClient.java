package com.xander.lab.agent.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.common.ModelCallResult;
import com.xander.lab.common.ModelUsage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

/**
 * Calls an OpenAI-compatible Responses endpoint to drive the agent loop.
 * Configuration belongs to the agent module; deployment defaults may reuse
 * the blog model environment variables without coupling Java modules.
 */
@Slf4j
@Component
public class AgentModelClient {
    private static final Set<Integer> TRANSIENT_STATUS = Set.of(429, 500, 502, 503, 504);
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_BACKOFF_MILLIS = 500L;
    private final AgentModelProperties properties;
    private final ObjectMapper objectMapper;

    public AgentModelClient(AgentModelProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * Asks the model to pick the next step: either conclude with a reply or
     * invoke one of the provided tools. Returns a JSON decision object with usage.
     */
    public ModelCallResult decideNextStep(String context, String toolDescriptions) {
        return decideNextStepStream(context, toolDescriptions, ignored -> { });
    }

    /**
     * Streams the model's final user-facing reply while retaining tool-action
     * JSON inside the orchestration boundary. The response is still parsed as
     * one authoritative decision object after the upstream stream completes.
     */
    public ModelCallResult decideNextStepStream(String context, String toolDescriptions,
                                         Consumer<String> onAnswerDelta) {
        return decideNextStepStream(context, toolDescriptions, onAnswerDelta, () -> false);
    }

    /** Retries only transient failures and never retries after a user-visible reply delta. */
    public ModelCallResult decideNextStepStream(String context, String toolDescriptions,
                                         Consumer<String> onAnswerDelta,
                                         BooleanSupplier cancellationRequested) {
        AtomicBoolean visibleDelta = new AtomicBoolean();
        Consumer<String> guardedDelta = delta -> {
            checkCancellation(cancellationRequested);
            visibleDelta.set(true);
            onAnswerDelta.accept(delta);
        };
        for (int attempt = 1; ; attempt++) {
            checkCancellation(cancellationRequested);
            try {
                return decideNextStepStreamOnce(
                        context, toolDescriptions, guardedDelta, cancellationRequested);
            } catch (ModelGatewayException exception) {
                if (!TRANSIENT_STATUS.contains(exception.status())
                        || visibleDelta.get() || attempt >= MAX_ATTEMPTS) {
                    throw exception;
                }
                log.warn("agent model transient failure on attempt {} (HTTP {}), retrying",
                        attempt, exception.status());
                sleepBackoff(attempt);
            }
        }
    }

    private ModelCallResult decideNextStepStreamOnce(String context, String toolDescriptions,
                                               Consumer<String> onAnswerDelta,
                                               BooleanSupplier cancellationRequested) {
        requireConfigured();
        Map<String, Object> payload = requestPayload(context, toolDescriptions);
        payload.put("stream", true);

        HttpURLConnection connection = null;
        StringBuilder output = new StringBuilder();
        ModelUsage usage = ModelUsage.NONE;
        DecisionReplyDecoder replyDecoder = new DecisionReplyDecoder(onAnswerDelta);
        try {
            connection = (HttpURLConnection) URI.create(
                    trimTrailingSlash(properties.getBaseUrl()) + "/responses")
                    .toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Authorization", "Bearer " + properties.getApiKey());
            connection.setRequestProperty("Content-Type", MediaType.APPLICATION_JSON_VALUE);
            connection.setRequestProperty("Accept", MediaType.TEXT_EVENT_STREAM_VALUE);
            connection.setConnectTimeout(Math.max(1, properties.getConnectTimeoutMillis()));
            connection.setReadTimeout(Math.max(1, properties.getReadTimeoutMillis()));
            connection.setDoOutput(true);
            connection.getOutputStream().write(objectMapper.writeValueAsBytes(payload));

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new ModelGatewayException(status,
                        "模型服务请求失败（HTTP " + status + "），请稍后重试");
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    checkCancellation(cancellationRequested);
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if (data.isEmpty() || "[DONE]".equals(data)) continue;
                    JsonNode event = objectMapper.readTree(data);
                    String type = event.path("type").asText();
                    if ("response.output_text.delta".equals(type)) {
                        appendDecisionDelta(output, replyDecoder, event.path("delta").asText(""));
                    } else if ("response.output_text.done".equals(type) && output.isEmpty()) {
                        appendDecisionDelta(output, replyDecoder, event.path("text").asText(""));
                    } else if ("response.completed".equals(type)) {
                        if (output.isEmpty()) {
                            appendDecisionDelta(output, replyDecoder,
                                    defaultText(extractOutputText(event.path("response"))));
                        }
                        usage = ModelUsage.fromJson(event.path("response").path("usage"));
                        break;
                    } else if ("error".equals(type)) {
                        throw new ModelGatewayException(502, "模型服务暂时不可用，请稍后重试");
                    }
                }
            }

            if (!StringUtils.hasText(output)) {
                throw new IllegalStateException("模型没有返回决策结果");
            }
            JsonNode decision = objectMapper.readTree(stripCodeFence(output.toString()));
            validateDecision(decision);
            return ModelCallResult.of(decision, usage);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("模型决策结果不是有效的 JSON，请重试", exception);
        } catch (IOException exception) {
            throw new ModelGatewayException(503, "模型服务连接中断，请稍后重试", exception);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void checkCancellation(BooleanSupplier cancellationRequested) {
        if (cancellationRequested != null && cancellationRequested.getAsBoolean()) {
            throw new CancellationException("用户已停止当前智能体任务");
        }
    }

    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(RETRY_BACKOFF_MILLIS * attempt);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CancellationException("智能体模型调用已中断");
        }
    }

    private void appendDecisionDelta(StringBuilder output, DecisionReplyDecoder decoder, String delta) {
        if (!StringUtils.hasLength(delta)) return;
        output.append(delta);
        decoder.accept(delta);
    }

    private Map<String, Object> requestPayload(String context, String toolDescriptions) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", properties.getModel());
        payload.put("store", false);
        payload.put("instructions", instructions(toolDescriptions));
        payload.put("input", context);
        if (properties.isWebSearchEnabled()) {
            payload.put("tools", List.of(Map.of("type", "web_search")));
        }
        return payload;
    }

    private void requireConfigured() {
        if (!StringUtils.hasText(properties.getApiKey())
                || !StringUtils.hasText(properties.getModel())) {
            throw new IllegalStateException("博客智能体尚未配置模型服务，请设置 BLOG_AGENT_API_KEY 和 BLOG_AGENT_MODEL");
        }
    }

    private void validateDecision(JsonNode decision) {
        if (decision == null || !decision.isObject() || !decision.has("done")) {
            throw new IllegalStateException("模型决策结果缺少 done 字段，请重试");
        }
        if (decision.path("done").asBoolean(false)) {
            if (!StringUtils.hasText(decision.path("reply").asText())) {
                throw new IllegalStateException("模型最终回复为空，请重试");
            }
            return;
        }
        if (!StringUtils.hasText(decision.path("action").path("tool").asText())) {
            throw new IllegalStateException("模型工具决策缺少工具名，请重试");
        }
    }

    private String instructions(String toolDescriptions) {
        return "你是 Xander Lab 博客工作台的智能体调度器。你的任务是把用户的对话诉求拆解成可执行步骤，"
                + "通过调用工具完成任务，并在完成后用中文回复用户。\n"
                + "可调用工具：\n" + toolDescriptions + "\n"
                + "决策规则：\n"
                + "1. 需要执行操作时，调用工具并等待工具结果；不要臆造工具结果。\n"
                + "2. 工具执行失败时，尝试换一种工具参数或说明失败原因并结束。\n"
                + "3. 用户诉求已达成、或无需调用任何工具时，直接给出最终回复。\n"
                + "4. 必须且只能返回单行 JSON，不要使用 Markdown，不要输出 JSON 之外的任何文字。"
                + "字段顺序必须严格为 thought、done、reply、action；最终回复放在 reply 中，便于安全流式展示。JSON 结构：\n"
                + "{\"thought\":\"本步的简要思考\",\"done\":false,\"reply\":\"最终回复，done 为 true 时必须填写\","
                + "\"action\":{\"tool\":\"工具名\",\"args\":{\"工具参数名\":\"值\"}}}。\n"
                + "当需要调用工具时 done 为 false 且 action 指向要调用的工具；"
                + "当准备向用户输出最终结果时 done 为 true 且 reply 填写最终回复。";
    }

    private String extractOutputText(JsonNode response) {
        if (response == null || response.isMissingNode()) return null;
        String direct = response.path("output_text").asText();
        if (StringUtils.hasText(direct)) return direct;
        for (JsonNode output : response.path("output")) {
            for (JsonNode content : output.path("content")) {
                String text = content.path("text").asText();
                if (StringUtils.hasText(text)) return text;
            }
        }
        String chatContent = response.path("choices").path(0).path("message").path("content").asText();
        return StringUtils.hasText(chatContent) ? chatContent : null;
    }

    private String stripCodeFence(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                return trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return trimmed;
    }

    private String trimTrailingSlash(String value) {
        return value != null && value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String defaultText(String value) {
        return value == null ? "" : value;
    }

    /** Extracts a decoded prefix of the top-level reply string from partial decision JSON. */
    static final class DecisionReplyDecoder {
        private final Consumer<String> listener;
        private final StringBuilder source = new StringBuilder();
        private int emittedLength;

        DecisionReplyDecoder(Consumer<String> listener) {
            this.listener = listener == null ? ignored -> { } : listener;
        }

        void accept(String delta) {
            source.append(delta);
            DecisionSnapshot snapshot = inspect(source.toString());
            if (!Boolean.TRUE.equals(snapshot.done()) || snapshot.reply() == null) return;
            String reply = snapshot.reply();
            int safeEnd = reply.length();
            if (safeEnd > 0 && Character.isHighSurrogate(reply.charAt(safeEnd - 1))) safeEnd--;
            if (safeEnd <= emittedLength) return;
            listener.accept(reply.substring(emittedLength, safeEnd));
            emittedLength = safeEnd;
        }

        private DecisionSnapshot inspect(String json) {
            int cursor = json.indexOf('{');
            if (cursor < 0) return new DecisionSnapshot(null, null);
            cursor++;
            Boolean done = null;
            String reply = null;
            while (cursor < json.length()) {
                cursor = skipWhitespaceAndCommas(json, cursor);
                if (cursor >= json.length() || json.charAt(cursor) == '}') break;
                ParsedString key = parseString(json, cursor);
                if (key == null || !key.complete()) break;
                cursor = skipWhitespace(json, key.end());
                if (cursor >= json.length() || json.charAt(cursor) != ':') break;
                cursor = skipWhitespace(json, cursor + 1);
                if (cursor >= json.length()) break;

                if ("done".equals(key.value())) {
                    if (json.startsWith("true", cursor)) done = true;
                    else if (json.startsWith("false", cursor)) done = false;
                } else if ("reply".equals(key.value()) && json.charAt(cursor) == '"') {
                    ParsedString parsedReply = parseString(json, cursor);
                    if (parsedReply != null) reply = parsedReply.value();
                }

                int next = skipValue(json, cursor);
                if (next < 0) break;
                cursor = next;
            }
            return new DecisionSnapshot(done, reply);
        }

        private int skipWhitespaceAndCommas(String value, int index) {
            int cursor = skipWhitespace(value, index);
            if (cursor < value.length() && value.charAt(cursor) == ',') {
                return skipWhitespace(value, cursor + 1);
            }
            return cursor;
        }

        private int skipWhitespace(String value, int index) {
            int cursor = index;
            while (cursor < value.length() && Character.isWhitespace(value.charAt(cursor))) cursor++;
            return cursor;
        }

        private int skipValue(String value, int index) {
            char first = value.charAt(index);
            if (first == '"') {
                ParsedString parsed = parseString(value, index);
                return parsed != null && parsed.complete() ? parsed.end() : -1;
            }
            if (first == '{' || first == '[') {
                char closing = first == '{' ? '}' : ']';
                int depth = 0;
                boolean inString = false;
                boolean escaped = false;
                for (int cursor = index; cursor < value.length(); cursor++) {
                    char character = value.charAt(cursor);
                    if (inString) {
                        if (escaped) escaped = false;
                        else if (character == '\\') escaped = true;
                        else if (character == '"') inString = false;
                        continue;
                    }
                    if (character == '"') inString = true;
                    else if (character == first) depth++;
                    else if (character == closing && --depth == 0) return cursor + 1;
                }
                return -1;
            }
            for (int cursor = index; cursor < value.length(); cursor++) {
                char character = value.charAt(cursor);
                if (character == ',' || character == '}') return cursor;
            }
            return -1;
        }

        private ParsedString parseString(String value, int openingQuote) {
            if (openingQuote >= value.length() || value.charAt(openingQuote) != '"') return null;
            StringBuilder decoded = new StringBuilder();
            for (int cursor = openingQuote + 1; cursor < value.length(); cursor++) {
                char character = value.charAt(cursor);
                if (character == '"') return new ParsedString(decoded.toString(), cursor + 1, true);
                if (character != '\\') {
                    decoded.append(character);
                    continue;
                }
                if (++cursor >= value.length()) return new ParsedString(decoded.toString(), value.length(), false);
                char escape = value.charAt(cursor);
                switch (escape) {
                    case '"', '\\', '/' -> decoded.append(escape);
                    case 'b' -> decoded.append('\b');
                    case 'f' -> decoded.append('\f');
                    case 'n' -> decoded.append('\n');
                    case 'r' -> decoded.append('\r');
                    case 't' -> decoded.append('\t');
                    case 'u' -> {
                        if (cursor + 4 >= value.length()) {
                            return new ParsedString(decoded.toString(), value.length(), false);
                        }
                        String hex = value.substring(cursor + 1, cursor + 5);
                        try {
                            decoded.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException ignored) {
                            return new ParsedString(decoded.toString(), value.length(), false);
                        }
                        cursor += 4;
                    }
                    default -> {
                        return new ParsedString(decoded.toString(), value.length(), false);
                    }
                }
            }
            return new ParsedString(decoded.toString(), value.length(), false);
        }
    }

    private record ParsedString(String value, int end, boolean complete) {
    }

    private record DecisionSnapshot(Boolean done, String reply) {
    }

    private static final class ModelGatewayException extends IllegalStateException {
        private final int status;

        private ModelGatewayException(int status, String message) {
            super(message);
            this.status = status;
        }

        private ModelGatewayException(int status, String message, Throwable cause) {
            super(message, cause);
            this.status = status;
        }

        private int status() {
            return status;
        }
    }
}
