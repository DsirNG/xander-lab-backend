package com.xander.lab.agent.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.admin.application.ModelGateway;
import com.xander.lab.admin.application.ModelGateway.ModelEndpoint;
import com.xander.lab.common.ModelCallResult;
import com.xander.lab.common.ModelUsage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.Locale;
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
 * When the admin model gateway returns candidates for the agent feature they
 * take precedence; otherwise the environment configuration is used as before.
 */
@Slf4j
@Component
public class AgentModelClient {
    private static final Set<Integer> TRANSIENT_STATUS = Set.of(429, 500, 502, 503, 504);
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_BACKOFF_MILLIS = 500L;
    private static final List<String> WEB_SEARCH_MARKERS = List.of(
            "最新", "今天", "当前", "现在", "新闻", "搜索", "搜一下", "查找", "联网",
            "资料来源", "引用来源", "链接", "网址", "latest", "today", "current",
            "news", "search", "source", "link", "url");
    private final AgentModelProperties properties;
    private final ObjectMapper objectMapper;
    private final ModelGateway gateway;

    /** 兼容仅依赖环境变量的构造器（测试/独立组件）：关闭管理台网关。 */
    public AgentModelClient(AgentModelProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, ModelGateway.disabled());
    }

    /** Spring 构造器注入入口。类里有多个构造器，必须显式标注 @Autowired，
     *  否则容器退回找无参构造器导致启动失败。 */
    @Autowired
    public AgentModelClient(AgentModelProperties properties, ObjectMapper objectMapper, ModelGateway gateway) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.gateway = gateway;
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

    /**
     * Retries only transient failures and never retries after a user-visible reply delta.
     * When the admin gateway configured a fallback endpoint, exhausted primary attempts
     * fail over to it, still never re-streaming deltas that were already delivered.
     */
    public ModelCallResult decideNextStepStream(String context, String toolDescriptions,
                                         Consumer<String> onAnswerDelta,
                                         BooleanSupplier cancellationRequested) {
        List<ModelEndpoint> endpoints = endpoints();
        AtomicBoolean visibleDelta = new AtomicBoolean();
        Consumer<String> guardedDelta = delta -> {
            checkCancellation(cancellationRequested);
            visibleDelta.set(true);
            onAnswerDelta.accept(delta);
        };
        RuntimeException lastFailure = new IllegalStateException("模型服务未配置");
        for (int index = 0; index < endpoints.size(); index++) {
            ModelEndpoint endpoint = endpoints.get(index);
            try {
                return decideNextStepStreamPerEndpoint(endpoint, context, toolDescriptions,
                        guardedDelta, visibleDelta, cancellationRequested);
            } catch (RuntimeException exception) {
                lastFailure = exception;
                if (visibleDelta.get() || index == endpoints.size() - 1) {
                    throw exception;
                }
                log.warn("agent model endpoint {} failed, failing over to the next provider reason={} message={}",
                        endpoint.providerId(), exception.getClass().getSimpleName(), exception.getMessage());
            }
        }
        throw lastFailure;
    }

    private ModelCallResult decideNextStepStreamPerEndpoint(ModelEndpoint endpoint, String context,
                                                     String toolDescriptions, Consumer<String> onAnswerDelta,
                                                     AtomicBoolean visibleDelta,
                                                     BooleanSupplier cancellationRequested) {
        for (int attempt = 1; ; attempt++) {
            checkCancellation(cancellationRequested);
            try {
                return decideNextStepStreamOnce(endpoint, context, toolDescriptions,
                        onAnswerDelta, cancellationRequested);
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

    /** 解析候选端点：网关启用时由功能模型配置驱动（未配置由网关抛错）；网关关闭时回退环境变量（原行为）。 */
    private List<ModelEndpoint> endpoints() {
        List<ModelEndpoint> candidates = gateway.candidates(ModelGateway.FEATURE_AGENT);
        if (!candidates.isEmpty()) {
            return candidates;
        }
        requireConfigured();
        return List.of(new ModelEndpoint(0L, properties.getBaseUrl(),
                properties.getApiKey(), properties.getModel()));
    }

    private ModelCallResult decideNextStepStreamOnce(ModelEndpoint endpoint, String context, String toolDescriptions,
                                                Consumer<String> onAnswerDelta,
                                                BooleanSupplier cancellationRequested) {
        long requestStartedNanos = System.nanoTime();
        Map<String, Object> payload = requestPayload(endpoint.model(), context, toolDescriptions);
        payload.put("stream", true);
        boolean directConversation = !StringUtils.hasText(toolDescriptions);

        HttpURLConnection connection = null;
        StringBuilder output = new StringBuilder();
        ModelUsage usage = ModelUsage.NONE;
        DecisionReplyDecoder replyDecoder = new DecisionReplyDecoder(onAnswerDelta);
        try {
            connection = (HttpURLConnection) URI.create(
                    trimTrailingSlash(endpoint.baseUrl()) + "/responses")
                    .toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Authorization", "Bearer " + endpoint.apiKey());
            connection.setRequestProperty("Content-Type", MediaType.APPLICATION_JSON_VALUE);
            connection.setRequestProperty("Accept", MediaType.TEXT_EVENT_STREAM_VALUE);
            connection.setConnectTimeout(Math.max(1, properties.getConnectTimeoutMillis()));
            connection.setReadTimeout(Math.max(1, properties.getReadTimeoutMillis()));
            connection.setDoOutput(true);
            connection.getOutputStream().write(objectMapper.writeValueAsBytes(payload));

            int status = connection.getResponseCode();
            long headersMillis = elapsedMillis(requestStartedNanos);
            if (status < 200 || status >= 300) {
                throw new ModelGatewayException(status,
                        "模型服务请求失败（HTTP " + status + "），请稍后重试");
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                boolean firstOutputLogged = false;
                while ((line = reader.readLine()) != null) {
                    checkCancellation(cancellationRequested);
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if (data.isEmpty() || "[DONE]".equals(data)) continue;
                    JsonNode event = objectMapper.readTree(data);
                    String type = event.path("type").asText();
                    if ("response.output_text.delta".equals(type)) {
                        if (!firstOutputLogged) {
                            firstOutputLogged = true;
                            log.info("agent model first output provider={} model={} headersMs={} firstOutputMs={}",
                                    endpoint.providerId(), endpoint.model(), headersMillis,
                                    elapsedMillis(requestStartedNanos));
                        }
                        appendOutputDelta(output, replyDecoder, event.path("delta").asText(""),
                                directConversation, onAnswerDelta);
                    } else if (!StringUtils.hasText(type)
                            && StringUtils.hasLength(event.path("choices").path(0)
                            .path("delta").path("content").asText(""))) {
                        appendOutputDelta(output, replyDecoder, event.path("choices").path(0)
                                        .path("delta").path("content").asText(""),
                                directConversation, onAnswerDelta);
                    } else if ("response.output_text.done".equals(type) && output.isEmpty()) {
                        appendOutputDelta(output, replyDecoder, event.path("text").asText(""),
                                directConversation, onAnswerDelta);
                    } else if ("response.content_part.done".equals(type) && output.isEmpty()) {
                        appendOutputDelta(output, replyDecoder, event.path("part").path("text").asText(""),
                                directConversation, onAnswerDelta);
                    } else if ("response.output_item.done".equals(type) && output.isEmpty()) {
                        appendOutputDelta(output, replyDecoder, extractContentText(event.path("item")),
                                directConversation, onAnswerDelta);
                    } else if ("response.completed".equals(type)) {
                        if (output.isEmpty()) {
                            appendOutputDelta(output, replyDecoder,
                                    defaultText(extractOutputText(event.path("response"))),
                                    directConversation, onAnswerDelta);
                        }
                        usage = ModelUsage.fromJson(event.path("response").path("usage"));
                        log.info("agent model completed provider={} model={} totalMs={}",
                                endpoint.providerId(), endpoint.model(), elapsedMillis(requestStartedNanos));
                        break;
                    } else if ("error".equals(type)) {
                        throw new ModelGatewayException(502, "模型服务暂时不可用，请稍后重试");
                    }
                }
            }

            if (!StringUtils.hasText(output)) {
                throw new IllegalStateException("模型没有返回决策结果");
            }
            if (directConversation) {
                var decision = objectMapper.createObjectNode();
                decision.put("done", true);
                decision.put("reply", output.toString());
                decision.put("thought", "");
                decision.putNull("action");
                return ModelCallResult.of(decision, usage, endpoint.model());
            }
            JsonNode decision = normalizeDecisionArgs(parseDecision(output.toString()));
            validateDecision(decision);
            return ModelCallResult.of(decision, usage, endpoint.model());
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

    /** Plain chat bypasses the orchestration JSON envelope so its first upstream token is visible immediately. */
    private void appendOutputDelta(StringBuilder output, DecisionReplyDecoder decoder, String delta,
                                   boolean directConversation, Consumer<String> onAnswerDelta) {
        if (!StringUtils.hasLength(delta)) return;
        output.append(delta);
        if (directConversation) {
            onAnswerDelta.accept(delta);
            return;
        }
        decoder.accept(delta);
    }

    private Map<String, Object> requestPayload(String model, String context, String toolDescriptions) {
        boolean webSearchEnabled = properties.isWebSearchEnabled() && needsWebSearch(context);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("store", false);
        payload.put("instructions", instructions(toolDescriptions, webSearchEnabled));
        payload.put("input", context);
        if (StringUtils.hasText(toolDescriptions)) {
            payload.put("text", structuredDecisionText());
        }
        String reasoningEffort = normalizedReasoningEffort(model);
        if (reasoningEffort != null) {
            payload.put("reasoning", Map.of("effort", reasoningEffort));
        }
        if (webSearchEnabled) {
            payload.put("tools", List.of(Map.of("type", "web_search")));
        }
        return payload;
    }

    /**
     * Tool turns use Responses structured output so JSON validity is enforced by the provider,
     * rather than relying only on prompt adherence. Tool args are encoded as a JSON string in the
     * strict schema, then normalized back to an object before orchestration.
     */
    private JsonNode structuredDecisionText() {
        var text = objectMapper.createObjectNode();
        var format = text.putObject("format");
        format.put("type", "json_schema");
        format.put("name", "agent_decision");
        format.put("strict", true);

        var schema = format.putObject("schema");
        schema.put("type", "object");
        var properties = schema.putObject("properties");
        properties.putObject("done").put("type", "boolean");
        properties.putObject("reply").put("type", "string");
        properties.putObject("thought").put("type", "string");
        var action = properties.putObject("action");
        var actionVariants = action.putArray("anyOf");
        var actionObject = actionVariants.addObject();
        actionObject.put("type", "object");
        var actionProperties = actionObject.putObject("properties");
        actionProperties.putObject("tool").put("type", "string");
        actionProperties.putObject("args")
                .put("type", "string")
                .put("description", "工具参数对象序列化后的单行 JSON 字符串");
        actionObject.putArray("required").add("tool").add("args");
        actionObject.put("additionalProperties", false);
        actionVariants.addObject().put("type", "null");
        schema.putArray("required").add("done").add("reply").add("thought").add("action");
        schema.put("additionalProperties", false);
        return text;
    }

    private boolean needsWebSearch(String context) {
        if (!StringUtils.hasText(context)) return false;
        String normalized = context.toLowerCase(Locale.ROOT);
        return WEB_SEARCH_MARKERS.stream().anyMatch(normalized::contains);
    }

    private long elapsedMillis(long startedNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    /** Only reasoning-capable model families receive this optional Responses API parameter. */
    private String normalizedReasoningEffort(String model) {
        String configured = properties.getReasoningEffort();
        if (!StringUtils.hasText(configured) || !supportsReasoningEffort(model)) return null;
        String normalized = configured.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("minimal", "low", "medium", "high", "xhigh").contains(normalized)) {
            throw new IllegalStateException("AGENT_MODEL_REASONING_EFFORT 配置无效");
        }
        return normalized;
    }

    private boolean supportsReasoningEffort(String model) {
        if (!StringUtils.hasText(model)) return false;
        String normalized = model.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("pro")) return false;
        return normalized.startsWith("gpt-5")
                || normalized.matches("o[134](?:-|$).*");
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

    private String instructions(String toolDescriptions, boolean webSearchEnabled) {
        String webSearchInstructions = webSearchEnabled
                ? "网页搜索能力：平台已提供 web_search。用户需要最新信息、外部事实核验、资料来源或链接时，"
                + "应直接使用网页搜索；不要声称平台不支持网页搜索。只有搜索工具实际调用失败时，"
                + "才能如实说明失败原因。网页搜索由模型服务执行，不属于下方业务工具 action。\n"
                : "网页搜索能力：当前未启用。不要编造搜索结果或声称已经联网核验。\n";
        boolean hasBusinessTools = StringUtils.hasText(toolDescriptions);
        if (!hasBusinessTools) {
            return "你是 DinQorAI 博客工作台的对话助手。本轮无需业务操作，请直接、自然地用中文回答用户。\n"
                    + webSearchInstructions
                    + "本轮是普通对话，不提供业务工具；直接回答用户，不要虚构或请求调用业务工具。\n"
                    + "直接输出给用户看的 Markdown 正文，不要输出 JSON、字段名、HTML 换行标签、"
                    + "内部协议内容或隐藏思考；段落和换行使用 Markdown。";
        }
        String roleInstructions = hasBusinessTools
                ? "你是 DinQorAI 博客工作台的智能体调度器。你的任务是把用户的对话诉求拆解成可执行步骤，"
                + "通过调用工具完成任务，并在完成后用中文回复用户。\n"
                : "你是 DinQorAI 博客工作台的对话助手。本轮无需业务操作，请直接、自然地用中文回答用户。\n";
        String businessTools = hasBusinessTools
                ? "可调用业务工具：\n" + toolDescriptions + "\n"
                : "本轮是普通对话，不提供业务工具；直接回答用户，不要虚构或请求调用业务工具。\n";
        return roleInstructions
                + webSearchInstructions
                + businessTools
                + "决策规则：\n"
                + "1. 需要执行操作时，调用工具并等待工具结果；不要臆造工具结果。\n"
                + "2. 工具执行失败时，尝试换一种工具参数或说明失败原因并结束。\n"
                + "3. 用户诉求已达成、或无需调用任何工具时，直接给出最终回复。\n"
                + "4. 必须且只能返回单行 JSON，不要输出 JSON 之外的任何文字。"
                + "reply 内容允许使用 Markdown 排版（加粗、有序/无序列表、标题、引用、表格等），"
                + "JSON 字符串内的换行必须转义为 \\n；代码片段必须使用带语言标记的围栏"
                + "（如 ```html、```css、```js），以便前端提供代码复制与 HTML 预览。"
                + "字段顺序必须严格为 done、reply、thought、action；把 reply 提前，便于尽早安全流式展示。JSON 结构：\n"
                + "{\"done\":false,\"reply\":\"最终回复，done 为 true 时必须填写\",\"thought\":\"本步的简要思考\","
                + "\"action\":{\"tool\":\"工具名\",\"args\":\"{\\\"工具参数名\\\":\\\"值\\\"}\"}}。\n"
                + "action.args 必须是工具参数对象序列化后的单行 JSON 字符串；"
                + "当需要调用工具时 done 为 false 且 action 指向要调用的工具；"
                + "当准备向用户输出最终结果时 done 为 true、reply 填写最终回复且 thought 必须为空，"
                + "不要在 reply 之前生成用户不可见的思考内容。";
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

    private String extractContentText(JsonNode item) {
        if (item == null || item.isMissingNode()) return "";
        for (JsonNode content : item.path("content")) {
            String text = content.path("text").asText();
            if (StringUtils.hasText(text)) return text;
        }
        return defaultText(item.path("text").asText(null));
    }

    /** Accepts compatible gateways that wrap an otherwise valid decision with prose or tags. */
    private JsonNode parseDecision(String raw) {
        String normalized = stripCodeFence(raw);
        try {
            return objectMapper.readTree(normalized);
        } catch (JsonProcessingException original) {
            for (int start = normalized.indexOf('{'); start >= 0;
                 start = normalized.indexOf('{', start + 1)) {
                int end = balancedObjectEnd(normalized, start);
                if (end < 0) continue;
                try {
                    JsonNode candidate = objectMapper.readTree(normalized.substring(start, end));
                    if (candidate.isObject() && candidate.has("done")) return candidate;
                } catch (JsonProcessingException ignored) {
                    // Try the next balanced object; gateways may prepend metadata-like fragments.
                }
            }
            throw new IllegalStateException("模型决策结果不是有效的 JSON，请重试", original);
        }
    }

    private JsonNode normalizeDecisionArgs(JsonNode decision) {
        JsonNode action = decision.path("action");
        JsonNode args = action.path("args");
        if (!action.isObject() || !args.isTextual()) return decision;
        try {
            JsonNode decoded = objectMapper.readTree(args.asText());
            if (decoded == null || !decoded.isObject()) {
                throw new IllegalStateException("模型工具参数不是有效的 JSON 对象，请重试");
            }
            ((com.fasterxml.jackson.databind.node.ObjectNode) action).set("args", decoded);
            return decision;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("模型工具参数不是有效的 JSON 对象，请重试", exception);
        }
    }

    private int balancedObjectEnd(String value, int start) {
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int index = start; index < value.length(); index++) {
            char character = value.charAt(index);
            if (quoted) {
                if (escaped) escaped = false;
                else if (character == '\\') escaped = true;
                else if (character == '"') quoted = false;
                continue;
            }
            if (character == '"') quoted = true;
            else if (character == '{') depth++;
            else if (character == '}' && --depth == 0) return index + 1;
        }
        return -1;
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
            if (snapshot.reply() == null) return;
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
