package com.xander.lab.agent.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.core.api.ModelGateway;
import com.xander.lab.core.api.ModelGateway.ModelEndpoint;
import com.xander.lab.common.ModelCallResult;
import com.xander.lab.common.ModelUsage;
import com.xander.lab.agent.domain.AgentAttachment;
import com.xander.lab.agent.domain.AgentInputTurn;
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
 * Calls an OpenAI-compatible model endpoint to drive the agent loop.
 * The endpoint's {@code apiStyle} picks the protocol: {@code RESPONSES} (default)
 * posts to {@code /responses}, {@code CHAT_COMPLETIONS} to {@code /chat/completions}.
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
     *
     * <p>单段文本的入口：整段作为一轮 user 发出。给只有一段自包含输入的调用方用
     * （子智能体的 brief、聚焦测试），主循环走 role 化的 {@link AgentInputTurn} 重载。</p>
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
        return decideNextStepStream(List.of(AgentInputTurn.user(context)), toolDescriptions,
                onAnswerDelta, () -> false);
    }

    /**
     * Retries only transient failures and never retries after a user-visible reply delta.
     * When the admin gateway configured a fallback endpoint, exhausted primary attempts
     * fail over to it, still never re-streaming deltas that were already delivered.
     */
    public ModelCallResult decideNextStepStream(List<AgentInputTurn> turns, String toolDescriptions,
                                         Consumer<String> onAnswerDelta,
                                         BooleanSupplier cancellationRequested) {
        return decideNextStepStream(turns, toolDescriptions, List.of(), onAnswerDelta, cancellationRequested);
    }

    /** Sends the latest turn's images and documents as native Responses multimodal inputs. */
    public ModelCallResult decideNextStepStream(List<AgentInputTurn> turns, String toolDescriptions,
                                         List<AgentAttachment> attachments,
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
                return decideNextStepStreamPerEndpoint(endpoint, turns, toolDescriptions, attachments,
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

    private ModelCallResult decideNextStepStreamPerEndpoint(ModelEndpoint endpoint, List<AgentInputTurn> turns,
                                                     String toolDescriptions, List<AgentAttachment> attachments,
                                                     Consumer<String> onAnswerDelta,
                                                     AtomicBoolean visibleDelta,
                                                     BooleanSupplier cancellationRequested) {
        for (int attempt = 1; ; attempt++) {
            checkCancellation(cancellationRequested);
            try {
                return decideNextStepStreamOnce(endpoint, turns, toolDescriptions, attachments,
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

    private ModelCallResult decideNextStepStreamOnce(ModelEndpoint endpoint, List<AgentInputTurn> turns,
                                                String toolDescriptions,
                                                List<AgentAttachment> attachments,
                                                Consumer<String> onAnswerDelta,
                                                BooleanSupplier cancellationRequested) {
        long requestStartedNanos = System.nanoTime();
        // 接口风格由配置决定：管理台把某个功能指到 CHAT_COMPLETIONS 时就走 /chat/completions，
        // 否则维持原先的 /responses。两种协议的 payload 与事件流都不一样，见下面两个分支。
        boolean chatCompletions = ModelGateway.API_STYLE_CHAT_COMPLETIONS.equals(endpoint.apiStyle());
        Map<String, Object> payload = chatCompletions
                ? chatCompletionsPayload(endpoint.model(), turns, toolDescriptions, attachments)
                : requestPayload(endpoint.model(), turns, toolDescriptions, attachments);
        payload.put("stream", true);
        boolean directConversation = !StringUtils.hasText(toolDescriptions);

        HttpURLConnection connection = null;
        StringBuilder output = new StringBuilder();
        ModelUsage usage = ModelUsage.NONE;
        DecisionReplyDecoder replyDecoder = new DecisionReplyDecoder(onAnswerDelta);
        try {
            connection = (HttpURLConnection) URI.create(
                    trimTrailingSlash(endpoint.baseUrl())
                            + (chatCompletions ? "/chat/completions" : "/responses"))
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
                    if (chatCompletions) {
                        // Chat Completions 流没有 type 字段，增量挂在 choices[0].delta.content 上，
                        // 以 data: [DONE] 收尾（上面已跳过）。用法统计只在末包出现。
                        if (event.has("error")) {
                            // 与 /responses 一样，上游可能先以 200 建流再用 error 包报错。
                            // 文案保持笼统（原文可能含账号/配额），原文只进日志。
                            log.warn("agent model upstream error event provider={} model={} detail={}",
                                    endpoint.providerId(), endpoint.model(), errorEventDetail(event));
                            throw new ModelGatewayException(502, "模型服务暂时不可用，请稍后重试");
                        }
                        String delta = event.path("choices").path(0).path("delta").path("content").asText("");
                        if (StringUtils.hasLength(delta)) {
                            if (!firstOutputLogged) {
                                firstOutputLogged = true;
                                log.info("agent model first output provider={} model={} headersMs={} firstOutputMs={}",
                                        endpoint.providerId(), endpoint.model(), headersMillis,
                                        elapsedMillis(requestStartedNanos));
                            }
                            appendOutputDelta(output, replyDecoder, delta, directConversation, onAnswerDelta);
                        }
                        JsonNode chatUsage = event.path("usage");
                        if (chatUsage.isObject()) usage = ModelUsage.fromJson(chatUsage);
                        String finish = event.path("choices").path(0).path("finish_reason").asText("");
                        if (StringUtils.hasText(finish)) {
                            log.info("agent model completed provider={} model={} totalMs={}",
                                    endpoint.providerId(), endpoint.model(), elapsedMillis(requestStartedNanos));
                            break;
                        }
                        continue;
                    }
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
                        // 上游以 200 建立流、再用 error 事件报错。
                        // 用户可见的文案必须保持笼统（供应商的原始描述可能含账号、
                        // 配额等内部信息，由 mapsUpstreamSseErrorsToASafeMessage 钉死），
                        // 但**日志里**必须留下原文，否则线上只能看到"暂时不可用"，
                        // 真实原因（配额、审核、上游超时）永远无从查起。
                        log.warn("agent model upstream error event provider={} model={} detail={}",
                                endpoint.providerId(), endpoint.model(), errorEventDetail(event));
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
            JsonNode decision = validateDecision(normalizeDecisionArgs(parseDecision(output.toString())));
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

    private Map<String, Object> requestPayload(String model, List<AgentInputTurn> turns, String toolDescriptions,
                                               List<AgentAttachment> attachments) {
        // 搜索能力开关只看配置，不再按关键词猜。词表猜不中时模型会以为自己不能联网，
        // 于是凭记忆作答；把决定权交给模型更符合智能体语义，实际搜索仍只在它调用时才发生。
        boolean webSearchEnabled = properties.isWebSearchEnabled();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("store", false);
        payload.put("instructions", instructions(toolDescriptions, webSearchEnabled));
        payload.put("input", roleStructuredInput(turns, attachments));
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
     * Chat Completions 形态的请求体。
     *
     * <p>与 Responses 的差别不是改个字段名：system 指令要降级成一条 {@code role=system}
     * 消息；{@code input} 要变成 {@code messages}；附件不是 input_image/input_file，
     * 而是 image_url 内容块；结构化输出走 {@code response_format.json_schema}。
     * 图片以外的文档在 Chat Completions 里没有对应块，直接丢弃——宁可少给一份材料，
     * 也不能拼出一个供应商会 400 的 body。</p>
     */
    private Map<String, Object> chatCompletionsPayload(String model, List<AgentInputTurn> turns,
                                                       String toolDescriptions, List<AgentAttachment> attachments) {
        boolean webSearchEnabled = properties.isWebSearchEnabled();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);

        List<Map<String, Object>> messages = new java.util.ArrayList<>();
        messages.add(Map.of("role", "system",
                "content", instructions(toolDescriptions, webSearchEnabled)));
        List<AgentInputTurn> effective = turns == null || turns.isEmpty()
                ? List.of(AgentInputTurn.user("")) : turns;
        int attachmentTurn = hasAttachments(attachments) ? lastUserTurnIndex(effective) : -1;
        for (int index = 0; index < effective.size(); index++) {
            AgentInputTurn turn = effective.get(index);
            Object content = index == attachmentTurn
                    ? chatCompletionsContent(turn.text(), attachments) : turn.text();
            messages.add(Map.of("role", turn.role(), "content", content));
        }
        if (hasAttachments(attachments) && attachmentTurn < 0) {
            messages.add(Map.of("role", AgentInputTurn.USER,
                    "content", chatCompletionsContent("", attachments)));
        }
        payload.put("messages", List.copyOf(messages));

        if (StringUtils.hasText(toolDescriptions)) {
            payload.put("response_format", chatCompletionsDecisionFormat());
        }
        String reasoningEffort = normalizedReasoningEffort(model);
        if (reasoningEffort != null) {
            payload.put("reasoning_effort", reasoningEffort);
        }
        if (webSearchEnabled) {
            payload.put("tools", List.of(Map.of("type", "web_search")));
        }
        return payload;
    }

    /** Chat Completions 的多模态内容块：文本 + 图片（文档形态在此协议下没有对应块，跳过）。 */
    private Object chatCompletionsContent(String text, List<AgentAttachment> attachments) {
        java.util.ArrayList<Map<String, Object>> content = new java.util.ArrayList<>();
        content.add(Map.of("type", "text", "text", text));
        for (AgentAttachment attachment : attachments) {
            if (attachment.image()) {
                content.add(Map.of(
                        "type", "image_url",
                        "image_url", Map.of("url", attachment.url())));
            }
        }
        return content.size() == 1 ? text : List.copyOf(content);
    }

    /** Chat Completions 的结构化输出：同一套 schema，换成 {@code response_format} 外壳。 */
    private Map<String, Object> chatCompletionsDecisionFormat() {
        JsonNode text = structuredDecisionText();
        Map<String, Object> format = new LinkedHashMap<>();
        format.put("type", "json_schema");
        format.put("json_schema", Map.of(
                "name", text.path("format").path("name").asText("agent_decision"),
                "strict", text.path("format").path("strict").asBoolean(true),
                "schema", objectMapper.convertValue(text.path("format").path("schema"), Map.class)));
        return format;
    }

    /**
     * 把轮次渲染成 Responses 的 input 数组：每轮一条 role 化消息。
     *
     * <p>附件挂在最后一轮 user 上——那一轮就是"当前这次诉求"，图片和文档属于它。
     * 若最后一轮不是 user（正常不会发生，装配层总以一轮 user 收尾），
     * 就补一轮只带附件的 user，避免把图片挂到模型自己说的话上。</p>
     */
    private Object roleStructuredInput(List<AgentInputTurn> turns, List<AgentAttachment> attachments) {
        List<AgentInputTurn> effective = turns == null || turns.isEmpty()
                ? List.of(AgentInputTurn.user("")) : turns;
        int attachmentTurn = hasAttachments(attachments) ? lastUserTurnIndex(effective) : -1;
        List<Map<String, Object>> input = new java.util.ArrayList<>();
        for (int index = 0; index < effective.size(); index++) {
            AgentInputTurn turn = effective.get(index);
            Object content = index == attachmentTurn
                    ? multimodalContent(turn.text(), attachments) : turn.text();
            input.add(Map.of("role", turn.role(), "content", content));
        }
        if (hasAttachments(attachments) && attachmentTurn < 0) {
            input.add(Map.of("role", AgentInputTurn.USER,
                    "content", multimodalContent("", attachments)));
        }
        return List.copyOf(input);
    }

    private boolean hasAttachments(List<AgentAttachment> attachments) {
        return attachments != null && !attachments.isEmpty();
    }

    private int lastUserTurnIndex(List<AgentInputTurn> turns) {
        for (int index = turns.size() - 1; index >= 0; index--) {
            if (turns.get(index).isUser()) return index;
        }
        return -1;
    }

    private Object multimodalContent(String text, List<AgentAttachment> attachments) {
        java.util.ArrayList<Map<String, Object>> content = new java.util.ArrayList<>();
        content.add(Map.of("type", "input_text", "text", text));
        for (AgentAttachment attachment : attachments) {
            if (attachment.image()) {
                content.add(Map.of(
                        "type", "input_image",
                        "image_url", attachment.url(),
                        "detail", "auto"));
            } else {
                content.add(Map.of(
                        "type", "input_file",
                        "file_url", attachment.url()));
            }
        }
        return List.copyOf(content);
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
        actionVariants.add(toolCallSchema());
        actionVariants.addObject().put("type", "null");
        // 一步多个调用：与 action 共用同一套元素形状（见 toolCallSchema），二选一使用。
        // 它同样进 required，是为了满足 strict 模式"属性必须全部声明"的要求；
        // 不认这个字段的中转网关不发它，编排层按缺失回落到 action，行为不变。
        var actions = properties.putObject("actions");
        var actionsVariants = actions.putArray("anyOf");
        var actionsArray = actionsVariants.addObject();
        actionsArray.put("type", "array");
        actionsArray.set("items", toolCallSchema());
        actionsVariants.addObject().put("type", "null");
        schema.putArray("required").add("done").add("reply").add("thought").add("action").add("actions");
        schema.put("additionalProperties", false);
        return text;
    }

    /**
     * 一次工具调用的 schema 形状。
     *
     * <p>{@code action} 与 {@code actions[]} 的元素必须是**逐字段相同**的一份定义：
     * 两边各写一遍迟早会漂移（改了一处忘了另一处），而漂移的表现是模型按 A 形状发、
     * 服务端按 B 形状解，只在特定分支上出错。所以只在这里定义一次。</p>
     */
    private ObjectNode toolCallSchema() {
        var call = objectMapper.createObjectNode();
        call.put("type", "object");
        var callProperties = call.putObject("properties");
        callProperties.putObject("tool").put("type", "string");
        callProperties.putObject("args")
                .put("type", "string")
                .put("description", "工具参数对象序列化后的单行 JSON 字符串");
        call.putArray("required").add("tool").add("args");
        call.put("additionalProperties", false);
        return call;
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

    /**
     * 该模型族是否接受 Responses 的 {@code reasoning.effort} 参数。
     *
     * <p>漏判的代价不是"少传一个可选字段"：不传时端点会用自己的默认档位，而中转上
     * 实测 grok-4.5 的默认是 {@code high}。那意味着每次对话都按高推理跑——实测同样
     * 一个"hi"，不传时 reasoning_tokens=566，传 {@code low} 时 180，成本同步降到约
     * 三分之一。上游负载因此显著抬高，而高负载正是 524 的温床。grok 族必须一起纳入。
     */
    private boolean supportsReasoningEffort(String model) {
        if (!StringUtils.hasText(model)) return false;
        String normalized = model.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("pro")) return false;
        return normalized.startsWith("gpt-5")
                || normalized.startsWith("grok")
                || normalized.matches("o[134](?:-|$).*");
    }

    private void requireConfigured() {
        if (!StringUtils.hasText(properties.getApiKey())
                || !StringUtils.hasText(properties.getModel())) {
            throw new IllegalStateException("博客智能体尚未配置模型服务，请设置 BLOG_AGENT_API_KEY 和 BLOG_AGENT_MODEL");
        }
    }

    /**
     * 校验决策，并把"没有工具可调却说自己没做完"的输出补成一次收口。
     *
     * <p>这种输出多来自必须等用户回答才能继续的流程（出完题等作答、向用户确认取舍）：
     * 模型真正想做的是"把这一轮交还给用户"，而协议里表达它的唯一方式是 done=true，
     * 于是它退化成 done=false + action 为空。此时 reply 已经流式推给用户了，
     * 按收口处理既符合它的本意，也免得用户读完题目再吃一个报错。</p>
     *
     * <p>reply 也是空的才算真的什么都没决定：这里不抛断整轮，交给编排循环按一次工具失败
     * 回灌纠正，模型还有重新选工具的机会。</p>
     *
     * <p>{@code actions} 非空时不做这个改写：那一批调用就是它的决定，改成收口等于把整批丢掉。</p>
     */
    private JsonNode validateDecision(JsonNode decision) {
        if (decision == null || !decision.isObject() || !decision.has("done")) {
            throw new IllegalStateException("模型决策结果缺少 done 字段，请重试");
        }
        if (decision.path("done").asBoolean(false)) {
            if (!StringUtils.hasText(decision.path("reply").asText())) {
                throw new IllegalStateException("模型最终回复为空，请重试");
            }
            return decision;
        }
        // actions 非空同样算"已经决定了要干什么"。漏判的后果很严重：下面那段会把决策改写成
        // done=true 并把 action 置空——模型报的一整批调用被静默丢掉，用户只收到一句"已完成"。
        if (hasBatch(decision)
                || StringUtils.hasText(decision.path("action").path("tool").asText())
                || !StringUtils.hasText(decision.path("reply").asText())) {
            return decision;
        }
        ObjectNode handBack = ((ObjectNode) decision).deepCopy();
        handBack.put("done", true);
        handBack.put("thought", "");
        handBack.putNull("action");
        handBack.putNull("actions");
        log.info("agent decision without a tool name carried a reply, handing the turn back to the user");
        return handBack;
    }

    /** {@code actions} 里是否至少有一个对象元素（元素工具名是否为空交给编排层判定并回灌纠正）。 */
    private boolean hasBatch(JsonNode decision) {
        JsonNode actions = decision.path("actions");
        if (!actions.isArray()) return false;
        for (JsonNode entry : actions) {
            if (entry != null && entry.isObject()) return true;
        }
        return false;
    }

    /**
     * Agent Policy：告诉模型它是一个可以自己做判断的智能体，而不是等着被路由的工作流节点。
     *
     * <p>刻意把"什么时候规划、什么时候动手、什么时候问、什么时候停、什么时候记"写成显式条款：
     * 能力边界不再由服务端的关键词规则代它决定，所以判断标准必须在这里讲清楚。</p>
     */
    private String instructions(String toolDescriptions, boolean webSearchEnabled) {
        String webSearchInstructions = webSearchEnabled
                ? "网页搜索能力：平台已提供 web_search。用户需要最新信息、外部事实核验、资料来源或链接时，"
                + "应直接使用网页搜索；不要声称平台不支持网页搜索。只有搜索工具实际调用失败时，"
                + "才能如实说明失败原因。网页搜索由模型服务执行，不属于下方业务工具 action。\n"
                : "网页搜索能力：当前未启用。不要编造搜索结果或声称已经联网核验。\n";
        if (!StringUtils.hasText(toolDescriptions)) {
            return "你是 DinQorAI 的对话助手，服务于用户的个人知识成长与内容创作。本轮没有可用工具，请直接、自然地用中文回答用户。\n"
                    + webSearchInstructions
                    + "直接输出给用户看的 Markdown 正文，不要输出 JSON、字段名、HTML 换行标签、"
                    + "内部协议内容或隐藏思考；段落和换行使用 Markdown。";
        }
        return "你是 DinQorAI 的个人知识智能体，服务于同一位用户的知识积累与内容创作。"
                + "你有自己的任务计划和长期记忆，可以自主决定做什么、按什么顺序做、什么时候算做完。\n"
                + webSearchInstructions
                + "可调用工具：\n" + toolDescriptions + "\n"
                + "行动准则：\n"
                + "1. 自己判断要不要用工具。凡是涉及用户自己的数据（知识点、掌握度、文章、计划、提醒、记忆）"
                + "或需要真实写入的操作，必须先调工具拿到真实结果再回答，不能凭印象作答，也不能只在嘴上答应。\n"
                + "2. 需要两步以上才能完成的诉求，先用 plan_tasks 写下计划再动手；每完成一步立刻更新它的状态。"
                + "一步就能做完的事不要写计划。\n"
                + "3. 计划里的步骤要么做完标 DONE，要么放弃标 DROPPED 并写明原因，不要留着不管就去收口。\n"
                + "4. 用户透露了跨会话仍然成立的信息（身份、稳定偏好、长期目标），或明确要你记住某件事时，"
                + "用 remember 写入长期记忆；每轮已自动注入相关记忆，不必为了读取而调用工具。\n"
                + "5. 缺少你自己拿不到的关键信息（用户的主观取舍、外部授权）时才向用户提问，一次问清；"
                + "能自己查到的事不要反问用户。\n"
                + "6. 工具失败时换参数或换工具再试；确实做不到就如实说明失败原因和影响，绝不把失败说成完成。\n"
                 + "7. 收口前自查：计划是否都收口了、承诺的操作是否真的执行了、结论是否有工具结果支撑。"
                 + "自检不通过时，服务端会把批评意见回灌给你，请按批评意见纠正后再收口。\n"
                 + "8. 数学内容必须使用 KaTeX 可渲染的 TeX 定界符：行内公式写成 $...$，独立公式写成 $$...$$；"
                 + "分数必须写成 \\frac{分子}{分母}，根式必须写成 \\sqrt{被开方数}，例如 $\\frac{3}{5}$、$\\frac{\\sqrt{3}}{2}$。"
                 + "不要用普通 3/5、√3/2 或 Unicode 上标来代替数学公式。\n"
                 + "9. 在完成讲评、分析、方案或其他有自然后续动作的任务后，可用一句简短的条件式建议引导用户，"
                 + "例如“如果你愿意，我也可以为你出一组纠错练习”。只建议与当前结果直接相关、用户可选择的下一步；"
                 + "不要自动执行该动作，也不要在没有自然后续时机械追加建议。\n"
                 + "上面的工具使用准则（T 开头那几条）与这份行动准则同等生效；两者冲突时以工具使用准则为准，"
                 + "因为它是针对本轮真实工具表写的。\n"
                 + "输出协议：\n"
                + "必须且只能返回单行 JSON，不要输出 JSON 之外的任何文字。"
                + "reply 内容允许使用 Markdown 排版（加粗、有序/无序列表、标题、引用、表格等），"
                + "JSON 字符串内的换行必须转义为 \\n；代码片段必须使用带语言标记的围栏"
                + "（如 ```html、```css、```js），以便前端提供代码复制与 HTML 预览。"
                + "字段顺序必须严格为 done、reply、thought、action、actions；把 reply 提前，便于尽早安全流式展示。JSON 结构：\n"
                + "{\"done\":false,\"reply\":\"最终回复，done 为 true 时必须填写\",\"thought\":\"给用户看的一句进度\","
                + "\"action\":{\"tool\":\"工具名\",\"args\":\"{\\\"工具参数名\\\":\\\"值\\\"}\"},\"actions\":null}。\n"
                + "thought 是直接显示给用户的一句进度说明，不是你的内部推理："
                + "写你正在为他做什么，一句话，不超过 30 字，用他的话讲（“先查一下你的知识库”“在整理成草稿”）。"
                + "不要写工具名、参数、字段名，不要复述上面的准则条款，也不要写“必须调用 xx 工具”这类内部理由——"
                + "用户看到的应该是进度，而不是系统怎么约束你的。真正的推理留在你自己的思考里，不要写进这个字段。\n"
                + "action.args 必须是工具参数对象序列化后的单行 JSON 字符串；"
                + "当需要调用工具时 done 为 false 且 action 指向要调用的工具；"
                + "当准备向用户输出最终结果时 done 为 true、reply 填写最终回复且 thought 必须为空，"
                + "不要在 reply 之前生成用户不可见的思考内容。\n"
                + "一步里要做几件互不依赖的事时，用 actions 数组一次报出，服务端会在**同一步**里全部执行完，"
                + "不必为了问三件事而分三步。actions 里每一项的形状与 action 完全相同；"
                + "用 actions 时把 action 设为 null，用 action 时把 actions 设为 null，两者只填一个。\n"
                + "只有**互不依赖、且都不改变任何状态**的查询才适合放进同一批（例如同时查知识点、查文章、查计划）。"
                + "有先后依赖的（后一步要用前一步的结果）必须分步；会写数据的（发布、同步、创建、修改、删除、发信、派发子任务）"
                + "一次只做一件，用 action 逐个来。同一个工具可以在一批里出现多次——参数不同就是不同的事"
                + "（例如一次查两个主题的知识点）；但不要把**完全相同的调用**写两遍，那是笔误，服务端会整批退回。\n"
                + "done=true 的含义是把这一轮交还给用户，而不只是整件事都做完了："
                 + "凡是要等用户回答之后才能继续的步骤（等用户作答、向用户确认取舍、请用户补信息），"
                 + "同样用 done=true 收口，等用户的下一条消息到达后再继续调工具。"
                 + "任何情况下都不要返回 done=false 而 action 为空：没有工具要调，就说明该收口了。";
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

    /**
     * 从上游 {@code error} 事件里取出人能看懂的一句描述。
     *
     * <p>Responses 与 Chat Completions 两种形态都认：前者的错误挂在 {@code error}
     * 对象上，后者在 {@code error} 里给 {@code message}。取不到就返回 null，由调用方
     * 退回通用文案。
     */
    private String errorEventDetail(JsonNode event) {
        JsonNode error = event.path("error");
        if (error.isMissingNode() || error.isNull()) return null;
        if (error.isTextual()) return defaultText(error.asText(null));
        for (String field : List.of("message", "detail", "type", "code")) {
            String value = error.path(field).asText();
            if (StringUtils.hasText(value)) return defaultText(value);
        }
        return null;
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

    /**
     * 把协议里的 {@code args}（单行 JSON 字符串）解码成对象。
     *
     * <p>{@code action} 与 {@code actions[]} 的每一项都要过这里。漏掉数组那一半的后果不是"参数没解析"，
     * 而是执行管道拿到的 {@code args} 是个字符串、直接以"工具参数必须是 JSON 对象"整批失败——
     * 模型每一步都被拒，而它按协议写得完全正确。</p>
     */
    private JsonNode normalizeDecisionArgs(JsonNode decision) {
        JsonNode action = decision.path("action");
        if (action.isObject()) decodeArgs((ObjectNode) action);
        JsonNode actions = decision.path("actions");
        if (actions.isArray()) {
            for (JsonNode entry : actions) {
                if (entry != null && entry.isObject()) decodeArgs((ObjectNode) entry);
            }
        }
        return decision;
    }

    private void decodeArgs(ObjectNode call) {
        JsonNode args = call.path("args");
        if (!args.isTextual()) return;
        try {
            JsonNode decoded = objectMapper.readTree(args.asText());
            if (decoded == null || !decoded.isObject()) {
                throw new IllegalStateException("模型工具参数不是有效的 JSON 对象，请重试");
            }
            call.set("args", decoded);
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
