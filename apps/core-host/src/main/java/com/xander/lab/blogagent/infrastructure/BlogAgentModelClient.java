package com.xander.lab.blogagent.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.core.api.ModelGateway;
import com.xander.lab.core.api.ModelGateway.ModelEndpoint;
import com.xander.lab.common.ModelCallResult;
import com.xander.lab.common.ModelUsage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Calls a Responses-compatible model endpoint. Model credentials are configuration only.
 *  When the admin model gateway returns candidates for the blog_agent feature they take
 *  precedence (primary first, fallback second); otherwise environment variables are used
 *  as before. Rest clients are cached per endpoint (base URL + key) and reused. */
@Slf4j
@Component
public class BlogAgentModelClient {
    /** Gateway statuses meaning the upstream model provider is temporarily unavailable.
     * 认证/校验类错误（400/401/422 等）不重试；仅对分类出的瞬态失败做有界重试。 */
    private static final Set<Integer> TRANSIENT_STATUS = Set.of(429, 500, 502, 503, 504);
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_BACKOFF_MILLIS = 500L;

    private final BlogAgentProperties properties;
    private final ObjectMapper objectMapper;
    private final ModelGateway gateway;
    private final ConcurrentHashMap<String, RestClient> restClients = new ConcurrentHashMap<>();

    /** 兼容仅依赖环境变量的构造器（测试/独立组件）：关闭管理台网关。 */
    public BlogAgentModelClient(BlogAgentProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, ModelGateway.disabled());
    }

    /** Spring 构造器注入入口。类里有多个构造器，必须显式标注 @Autowired，
     *  否则容器退回找无参构造器导致启动失败。 */
    @Autowired
    public BlogAgentModelClient(BlogAgentProperties properties, ObjectMapper objectMapper, ModelGateway gateway) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.gateway = gateway;
    }

    /** 解析候选端点：网关启用时由功能模型配置驱动（未配置由网关抛错）；网关关闭时回退环境变量（原行为）。 */
    private List<ModelEndpoint> endpoints() {
        List<ModelEndpoint> candidates = gateway.candidates(ModelGateway.FEATURE_BLOG_AGENT);
        if (!candidates.isEmpty()) {
            return candidates;
        }
        if (!StringUtils.hasText(properties.getApiKey()) || !StringUtils.hasText(properties.getModel())) {
            throw new IllegalStateException("博客生成助手尚未配置模型服务，请设置 BLOG_AGENT_API_KEY 和 BLOG_AGENT_MODEL");
        }
        return List.of(new ModelEndpoint(0L, properties.getBaseUrl(),
                properties.getApiKey(), properties.getModel()));
    }

    /** 每个端点一个 RestClient，按 baseUrl|apiKey 缓存复用，避免每次请求新建连接客户端。 */
    private RestClient restClientFor(ModelEndpoint endpoint) {
        return restClients.computeIfAbsent(endpoint.baseUrl() + "|" + endpoint.apiKey(), key -> {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(properties.getConnectTimeoutMillis());
            requestFactory.setReadTimeout(properties.getReadTimeoutMillis());
            return RestClient.builder()
                    .baseUrl(trimTrailingSlash(endpoint.baseUrl()))
                    .defaultHeader("Authorization", "Bearer " + endpoint.apiKey())
                    .requestFactory(requestFactory)
                    .build();
        });
    }

    public ModelCallResult analyze(String input) {
        return requestJson(
                "你是知识博客策划编辑。只分析用户真正想写什么、必须保留什么、哪些信息需要查证。"
                        + "不要写文章，不要搜索。仅返回 JSON："
                        + "{\"angle\":string,\"inputNature\":string,\"audience\":string,"
                        + "\"mustCover\":[string],\"questions\":[string],\"researchQueries\":[string]}。",
                input, false);
    }

    public ModelCallResult research(String input, JsonNode analysis) {
        return requestJson(
                "你是知识博客研究员。使用网页搜索核验和补全用户主题，只保留与文章主线直接相关的权威信息。"
                        + "不要写文章。仅返回 JSON："
                        + "{\"findings\":[{\"claim\":string,\"evidence\":string,\"url\":string,\"publisher\":string}],"
                        + "\"gaps\":[string],\"recommendedStructure\":[string]}。",
                "用户输入：\n" + input + "\n\n策划结果：\n" + analysis, true);
    }

    public ModelCallResult reviewArticle(String content, String userRequest) {
        return requestJson(
                "你是严谨的中文知识博客审校编辑。检查逻辑、事实表述、结构、Markdown 和用户要求。"
                        + "保留文章中的图片 Markdown，不要虚构来源。仅返回 JSON："
                        + "{\"content\":string,\"review\":string}。",
                "本轮用户要求：\n" + userRequest + "\n\n待审校文章：\n" + content, false);
    }

    /** Compliance gate for auto-publishing: rejects forbidden or unsafe content. */
    public ModelCallResult reviewCompliance(String title, String content) {
        return requestJson(
                "你是内容安全审核员。判断这篇文章是否包含色情、赌博、毒品、暴力仇恨、诈骗、违法违规等"
                        + "不得发布的敏感违规内容，或极低质量的水文。不要修改正文。仅返回 JSON："
                        + "{\"pass\":boolean,\"reasons\":[string]}。pass 表示可正常发布；"
                        + "不通过时 reasons 给出具体理由，通过时返回空数组。",
                "标题：\n" + title + "\n\n正文：\n" + content, false);
    }

    /** Generates a series of distinct article topics for a scheduled plan run. */
    public ModelCallResult planTopicSeries(String seed, String audience, String tone,
                                    int count, List<String> excludeTitles) {
        String excludeText = excludeTitles == null || excludeTitles.isEmpty()
                ? ""
                : "\n以下主题已使用过，禁止重复：\n" + String.join("\n", excludeTitles);
        return requestJson(
                "你是知识博客策划编辑。围绕用户给出的主题种子，细化出 " + count
                        + " 个彼此独立、具体可写、适合定时发布的中文知识博客标题。"
                        + "每个标题必须与主题种子高度相关但角度不同，避免空洞泛题。"
                        + excludeText
                        + "不要写文章。仅返回 JSON：{\"topics\":[string]，数量严格等于要求}。",
                "主题种子：\n" + seed
                        + (audience == null || audience.isBlank() ? "" : "\n目标读者：\n" + audience)
                        + (tone == null || tone.isBlank() ? "" : "\n文风语气：\n" + tone),
                false);
    }

    public ModelCallResult reviseArticleStream(String currentArticle, String instruction, Consumer<String> onDelta) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("store", false);
        payload.put("input", "当前文章：\n" + currentArticle + "\n\n用户本轮修改要求：\n" + instruction);
        payload.put("instructions", instructions()
                + "这是同一会话中的后续修改。必须以当前文章为基础执行用户要求，不要无故重写未涉及部分。"
                + "仍返回完整 JSON 文章对象，便于保存新版本。");
        return streamJson(payload, onDelta);
    }

    public ModelCallResult createArticle(String input) {
        return requestJsonOnceResult(articlePayload(input), "article");
    }

    /** Streams output tokens from a Responses-compatible endpoint and returns the final JSON article. */
    public ModelCallResult createArticleStream(String input, Consumer<String> onDelta) {
        return streamJson(articlePayload(input), onDelta);
    }

    private Map<String, Object> articlePayload(String input) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("store", false);
        payload.put("instructions", instructions());
        payload.put("input", "用户输入：\n" + input);
        if (properties.isWebSearchEnabled()) {
            payload.put("tools", List.of(Map.of("type", "web_search")));
        }
        return payload;
    }

    /** 非流式调用：逐端点尝试，每个端点内部有界重试；全部失败后抛出最后异常。 */
    private ModelCallResult requestJson(String instruction, String input, boolean webSearch) {
        RuntimeException lastFailure = new IllegalStateException("博客生成助手尚未配置模型服务");
        List<ModelEndpoint> endpoints = endpoints();
        for (int index = 0; index < endpoints.size(); index++) {
            ModelEndpoint endpoint = endpoints.get(index);
            try {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("model", endpoint.model());
                payload.put("store", false);
                payload.put("instructions", instruction);
                payload.put("input", input);
                if (webSearch && properties.isWebSearchEnabled()) {
                    payload.put("tools", List.of(Map.of("type", "web_search")));
                }
                JsonNode response = executeWithRetry("stage", () -> postJson(endpoint, payload));
                String text = extractOutputText(response);
                if (!StringUtils.hasText(text)) {
                    throw new IllegalStateException("模型没有返回可读取的阶段结果");
                }
                try {
                    return ModelCallResult.of(objectMapper.readTree(stripCodeFence(text)),
                            ModelUsage.fromJson(response.path("usage")), endpoint.model());
                } catch (Exception e) {
                    throw new IllegalStateException("模型阶段结果不是有效 JSON", e);
                }
            } catch (RuntimeException e) {
                lastFailure = e;
                if (index == endpoints.size() - 1) {
                    throw e;
                }
                log.warn("博客生成主模型端点（provider {}）失败，切换兜底模型", endpoint.providerId());
            }
        }
        throw lastFailure;
    }

    /** 单个完整 payload 的非流式调用（文章生成）：逐端点执行带重试的 POST。 */
    private ModelCallResult requestJsonOnceResult(Map<String, Object> payload, String description) {
        RuntimeException lastFailure = new IllegalStateException("博客生成助手尚未配置模型服务");
        List<ModelEndpoint> endpoints = endpoints();
        for (int index = 0; index < endpoints.size(); index++) {
            ModelEndpoint endpoint = endpoints.get(index);
            try {
                Map<String, Object> endpointPayload = new LinkedHashMap<>(payload);
                endpointPayload.put("model", endpoint.model());
                JsonNode response = executeWithRetry(description, () -> postJson(endpoint, endpointPayload));
                String text = extractOutputText(response);
                if (!StringUtils.hasText(text)) {
                    throw new IllegalStateException("模型没有返回可读取的文章结果");
                }
                try {
                    return ModelCallResult.of(objectMapper.readTree(stripCodeFence(text)),
                            ModelUsage.fromJson(response.path("usage")), endpoint.model());
                } catch (Exception e) {
                    throw new IllegalStateException("模型返回格式不正确，请重试", e);
                }
            } catch (RuntimeException e) {
                lastFailure = e;
                if (index == endpoints.size() - 1) {
                    throw e;
                }
                log.warn("博客生成主模型端点（provider {}）失败，切换兜底模型", endpoint.providerId());
            }
        }
        throw lastFailure;
    }

    /** Retries classified transient failures (gateway 429/5xx、连接超时/中断、SSE 上游错误) with
     * bounded backoff. Deltas already delivered to the UI are never re-sent, so stream calls only
     * retry while nothing has been emitted. */
    private JsonNode executeWithRetry(String description, Supplier<JsonNode> call) {
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                return call.get();
            } catch (RuntimeException e) {
                if (!isTransient(e)) throw e;
                if (attempt >= MAX_ATTEMPTS) {
                    log.warn("模型调用（{}）重试耗尽，共 {} 次尝试", description, attempt);
                    throw new ModelGatewayException(transientStatus(e), exhaustedMessage(e));
                }
                log.warn("模型调用（{}）瞬态失败（第 {} 次尝试）：{}，{}ms 后重试",
                        description, attempt, safeError(e), RETRY_BACKOFF_MILLIS * attempt);
                sleepBackoff(attempt);
            }
        }
    }

    /** 流式调用：逐端点尝试；端点内部有界重试且绝不重放已推送的 delta；主端点耗尽后切换兜底端点。 */
    private ModelCallResult streamJson(Map<String, Object> payload, Consumer<String> onDelta) {
        payload.put("stream", true);
        boolean[] visibleDeltaDelivered = {false};
        RuntimeException lastFailure = new IllegalStateException("博客生成助手尚未配置模型服务");
        List<ModelEndpoint> endpoints = endpoints();
        for (int index = 0; index < endpoints.size(); index++) {
            ModelEndpoint endpoint = endpoints.get(index);
            try {
                return streamJsonPerEndpoint(endpoint, payload, onDelta, visibleDeltaDelivered);
            } catch (RuntimeException e) {
                if (visibleDeltaDelivered[0]) throw e;
                lastFailure = e;
                if (index == endpoints.size() - 1) {
                    throw e;
                }
                log.warn("博客生成主模型端点（provider {}）失败，切换兜底模型", endpoint.providerId());
            }
        }
        throw lastFailure;
    }

    private ModelCallResult streamJsonPerEndpoint(ModelEndpoint endpoint, Map<String, Object> payload,
                                           Consumer<String> onDelta, boolean[] visibleDeltaDelivered) {
        payload.put("model", endpoint.model());
        Consumer<String> trackedOnDelta = delta -> {
            if (delta != null && !delta.isEmpty()) {
                visibleDeltaDelivered[0] = true;
            }
            onDelta.accept(delta);
        };
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                return streamOnce(endpoint, payload, trackedOnDelta);
            } catch (RuntimeException e) {
                if (visibleDeltaDelivered[0]) throw e;
                if (!isTransient(e)) throw e;
                if (attempt >= MAX_ATTEMPTS) {
                    log.warn("模型流式调用重试耗尽，共 {} 次尝试", attempt);
                    throw new ModelGatewayException(transientStatus(e), exhaustedMessage(e));
                }
                log.warn("模型流式调用瞬态失败（第 {} 次尝试）：{}，{}ms 后重试",
                        attempt, safeError(e), RETRY_BACKOFF_MILLIS * attempt);
                sleepBackoff(attempt);
            }
        }
    }

    private ModelCallResult streamOnce(ModelEndpoint endpoint, Map<String, Object> payload, Consumer<String> onDelta) {
        boolean chatCompletions = ModelGateway.API_STYLE_CHAT_COMPLETIONS.equals(endpoint.apiStyle());
        if (chatCompletions) {
            return streamOnceChatCompletions(endpoint, chatCompletionsPayload(payload), onDelta);
        }
        HttpURLConnection connection = null;
        StringBuilder output = new StringBuilder();
        ModelUsage usage = ModelUsage.NONE;
        try {
            connection = (HttpURLConnection) URI.create(trimTrailingSlash(endpoint.baseUrl()) + "/responses").toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Authorization", "Bearer " + endpoint.apiKey());
            connection.setRequestProperty("Content-Type", MediaType.APPLICATION_JSON_VALUE);
            connection.setRequestProperty("Accept", MediaType.TEXT_EVENT_STREAM_VALUE);
            connection.setDoOutput(true);
            connection.setConnectTimeout(Math.max(1, properties.getConnectTimeoutMillis()));
            connection.setReadTimeout(Math.max(1, properties.getReadTimeoutMillis()));
            connection.getOutputStream().write(objectMapper.writeValueAsBytes(payload));

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new ModelGatewayException(status, "模型服务请求失败（HTTP " + status + "）");
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if (data.isEmpty() || "[DONE]".equals(data)) continue;
                    JsonNode event = objectMapper.readTree(data);
                    String type = event.path("type").asText();
                    if ("response.output_text.delta".equals(type)) {
                        String delta = event.path("delta").asText("");
                        output.append(delta);
                        onDelta.accept(delta);
                    } else if ("response.output_text.done".equals(type) && output.isEmpty()) {
                        String text = event.path("text").asText("");
                        output.append(text);
                        onDelta.accept(text);
                    } else if ("response.completed".equals(type)) {
                        if (output.isEmpty()) {
                            String text = extractOutputText(event.path("response"));
                            output.append(text == null ? "" : text);
                        }
                        // A completed response may keep the upstream SSE connection open.
                        // The accumulated deltas are already the final article, so do not wait
                        // indefinitely for the provider to close the socket.
                        usage = ModelUsage.fromJson(event.path("response").path("usage"));
                        break;
                    } else if ("error".equals(type)) {
                        // 上游网关通过 SSE 事件而非 HTTP 状态码报告失败（如 Upstream request failed），
                        // 同样按瞬态失败重试；其余错误码保留给上层。
                        String code = streamErrorCode(event);
                        if ("upstream_error".equals(code) || "server_error".equals(code) || "overloaded".equals(code)) {
                            throw new ModelGatewayException(502, "模型服务暂时不可用，请稍后再试");
                        }
                        throw new IllegalStateException(StringUtils.hasText(code)
                                ? "模型服务返回错误（" + code + "）"
                                : "模型服务返回未知错误");
                    }
                }
            }
            if (!StringUtils.hasText(output)) throw new IllegalStateException("模型没有返回可读取的文章结果");
            return ModelCallResult.of(objectMapper.readTree(stripCodeFence(output.toString())), usage,
                    endpoint.model());
        } catch (IOException e) {
            // 流在产出任何 delta 前中断视为可重试的瞬态失败；已有输出时重试会重复推送内容，直接失败。
            if (output.isEmpty()) {
                throw new ModelGatewayException(503, "模型服务连接中断，请稍后再试");
            }
            throw new IllegalStateException("读取模型流式结果失败：" + e.getMessage(), e);
        } catch (CancellationException e) {
            throw e;
        } catch (IllegalStateException | ModelGatewayException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("读取模型流式结果失败：" + e.getMessage(), e);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /**
     * Chat Completions 的流式调用。
     *
     * <p>与 /responses 分支是两套事件格式：这里没有 {@code type}，增量在
     * {@code choices[0].delta.content}，以 {@code finish_reason} 表示收束。
     * 语义与重试策略和 Responses 分支保持一致——产出过可见 delta 就不再重试。</p>
     */
    private ModelCallResult streamOnceChatCompletions(ModelEndpoint endpoint, Map<String, Object> payload,
                                                      Consumer<String> onDelta) {
        HttpURLConnection connection = null;
        StringBuilder output = new StringBuilder();
        ModelUsage usage = ModelUsage.NONE;
        try {
            connection = (HttpURLConnection) URI.create(
                    trimTrailingSlash(endpoint.baseUrl()) + "/chat/completions").toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Authorization", "Bearer " + endpoint.apiKey());
            connection.setRequestProperty("Content-Type", MediaType.APPLICATION_JSON_VALUE);
            connection.setRequestProperty("Accept", MediaType.TEXT_EVENT_STREAM_VALUE);
            connection.setDoOutput(true);
            connection.setConnectTimeout(Math.max(1, properties.getConnectTimeoutMillis()));
            connection.setReadTimeout(Math.max(1, properties.getReadTimeoutMillis()));
            connection.getOutputStream().write(objectMapper.writeValueAsBytes(payload));

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new ModelGatewayException(status, "模型服务请求失败（HTTP " + status + "）");
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if (data.isEmpty() || "[DONE]".equals(data)) continue;
                    JsonNode event = objectMapper.readTree(data);
                    if (event.has("error")) {
                        throw new ModelGatewayException(502, "模型服务暂时不可用，请稍后再试");
                    }
                    JsonNode choice = event.path("choices").path(0);
                    String delta = choice.path("delta").path("content").asText("");
                    if (StringUtils.hasText(delta)) {
                        output.append(delta);
                        onDelta.accept(delta);
                    }
                    JsonNode chatUsage = event.path("usage");
                    if (chatUsage.isObject()) usage = ModelUsage.fromJson(chatUsage);
                    if (StringUtils.hasText(choice.path("finish_reason").asText(""))) break;
                }
            }
            if (!StringUtils.hasText(output)) throw new IllegalStateException("模型没有返回可读取的文章结果");
            return ModelCallResult.of(objectMapper.readTree(stripCodeFence(output.toString())), usage,
                    endpoint.model());
        } catch (IOException e) {
            if (output.isEmpty()) {
                throw new ModelGatewayException(503, "模型服务连接中断，请稍后再试");
            }
            throw new IllegalStateException("读取模型流式结果失败：" + e.getMessage(), e);
        } catch (CancellationException e) {
            throw e;
        } catch (IllegalStateException | ModelGatewayException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("读取模型流式结果失败：" + e.getMessage(), e);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /**
     * 把内部统一的 Responses 形态 payload 翻译成 Chat Completions 形态。
     *
     * <p>上游 builder 一律按 Responses 语义拼 {@code instructions}/{@code input}，
     * 在这里集中翻译，而不是让五六个 builder 各判断一次协议——协议是端点属性，
     * 与"这段提示词想说什么"无关，混在一起会让每个 builder 都要记住两套字段名。</p>
     */
    private Map<String, Object> chatCompletionsPayload(Map<String, Object> responsesPayload) {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : responsesPayload.entrySet()) {
            String key = entry.getKey();
            // store/instructions/input/tools 都是 Responses 专有键名，逐一转换后不再透传。
            if ("store".equals(key) || "instructions".equals(key) || "input".equals(key)) continue;
            payload.put(key, entry.getValue());
        }
        List<Map<String, Object>> messages = new java.util.ArrayList<>();
        Object instructions = responsesPayload.get("instructions");
        if (instructions instanceof String text && StringUtils.hasText(text)) {
            messages.add(Map.of("role", "system", "content", text));
        }
        Object input = responsesPayload.get("input");
        if (input instanceof List<?> turns) {
            for (Object turn : turns) {
                if (turn instanceof Map<?, ?> map && map.get("role") instanceof String role) {
                    messages.add(Map.of("role", role, "content", String.valueOf(map.get("content"))));
                }
            }
        } else if (input instanceof String text) {
            messages.add(Map.of("role", "user", "content", text));
        }
        payload.put("messages", List.copyOf(messages));
        return payload;
    }

    private JsonNode postJson(ModelEndpoint endpoint, Map<String, Object> payload) {
        boolean chatCompletions = ModelGateway.API_STYLE_CHAT_COMPLETIONS.equals(endpoint.apiStyle());
        try {
            return restClientFor(endpoint).post()
                    .uri(chatCompletions ? "/chat/completions" : "/responses")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(chatCompletions ? chatCompletionsPayload(payload) : payload)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            // 不把供应商原始响应体透传给用户，仅保留状态码并给出友好提示。
            int status = e.getStatusCode().value();
            throw new ModelGatewayException(status, "模型服务请求失败（HTTP " + status + "）");
        }
    }

    private String extractOutputText(JsonNode response) {
        if (response == null || response.isMissingNode()) return null;
        // 部分网关对上游失败返回 HTTP 200 但 body 带 error 对象，按瞬态失败处理。
        JsonNode error = response.path("error");
        if (error.isObject() && StringUtils.hasText(error.path("message").asText())) {
            throw new ModelGatewayException(502, "模型服务暂时不可用，请稍后再试");
        }
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

    private String streamErrorCode(JsonNode event) {
        JsonNode error = event.path("error");
        String code = error.path("type").asText();
        if (!StringUtils.hasText(code)) code = error.path("code").asText();
        if (!StringUtils.hasText(code)) code = event.path("code").asText();
        return code;
    }

    private boolean isTransient(RuntimeException e) {
        if (e instanceof ModelGatewayException gateway) return TRANSIENT_STATUS.contains(gateway.status);
        if (e instanceof RestClientResponseException response) {
            return TRANSIENT_STATUS.contains(response.getStatusCode().value());
        }
        return e instanceof ResourceAccessException;
    }

    private int transientStatus(RuntimeException e) {
        if (e instanceof ModelGatewayException gateway) return gateway.status;
        if (e instanceof RestClientResponseException response) return response.getStatusCode().value();
        return -1;
    }

    private String safeError(RuntimeException e) {
        int status = transientStatus(e);
        return e.getClass().getSimpleName() + (status > 0 ? "（HTTP " + status + "）" : "");
    }

    private String exhaustedMessage(RuntimeException e) {
        int status = transientStatus(e);
        String base = status > 0 ? "模型服务暂时不可用（HTTP " + status + "）" : "模型服务连接失败";
        return base + "，已自动重试 " + (MAX_ATTEMPTS - 1) + " 次仍失败，请稍后再试";
    }

    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(RETRY_BACKOFF_MILLIS * attempt);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("模型调用已被中断", interrupted);
        }
    }

    private String instructions() {
        return "你是 DinQorAI 的知识博客生成助手。把用户给出的主题、日记或项目问题，写成可发布的中文 Markdown 知识博客。"
                + "文章主干必须来自用户明确提到的主题、问题、场景与目标；你必须自行判断输入性质、合适读者与写作语气，用户不需要填写这些设置。输入形式只影响是否保留作者经历，不得改变文章主线。"
                + "只扩展直接帮助理解、验证或解决用户核心问题的知识。不要为了套模板强行加入因果链、知识图谱、对比、案例或排障清单；这些仅在用户提出、或它们能直接解释核心主题时才加入。"
                + "如用户提到原因、影响、机制、为什么、导致等关系，才应建立清晰的因果链。只有知识节点之间存在多个关键关系且图谱能提升理解时，才启用知识图谱。"
                + "你必须先澄清文章角度，再使用网页搜索工具核验或补充所有外部事实。用户的个人经历应保留为作者经历，不能伪装成普遍事实。"
                + "没有可靠来源时，不得写成确定事实。输出必须是且仅是 JSON："
                + "{\"title\":string,\"summary\":string,\"content\":string,\"outline\":string,\"writingBrief\":{\"inputNature\":string,\"audience\":string,\"tone\":string},\"contentBoundary\":{\"mustCover\":[string],\"relatedExpansion\":[string],\"outOfScope\":[string],\"optionalModules\":[string]},\"knowledgeGraph\":{\"enabled\":boolean,\"reason\":string,\"nodes\":[{\"id\":string,\"label\":string,\"description\":string}],\"edges\":[{\"from\":string,\"to\":string,\"relation\":string}]},\"categoryId\":string,\"tags\":[string],\"review\":string,\"illustrations\":[{\"placeholder\":string,\"title\":string,\"alt\":string,\"prompt\":string,\"purpose\":string}],\"sources\":[{\"title\":string,\"url\":string,\"publisher\":string,\"excerpt\":string,\"reliability\":string}]}。"
                + "writingBrief.inputNature 只能是 topic、project_context 或 journal 之一；audience 最多 120 个字符，tone 最多 60 个字符。"
                + "content 必须是完整文章，不含参考资料列表；文章末尾追加“## 参考资料”，以 Markdown 链接列出 sources。"
                + "仅当插图能显著帮助理解机制、流程、结构、对比或关键关系时才规划插图，最多 3 张，不要为装饰而配图。"
                + "每张插图必须在 content 的准确位置放置唯一占位符，格式严格为 <!-- illustration:英文短标识 -->，并在 illustrations 中返回完全相同的 placeholder。"
                + "illustrations 的 prompt 必须描述需要表达的知识关系、视觉结构、必要标签和风格；alt 必须准确说明图片传递的信息。无需插图时返回空数组，content 中不得出现插图占位符。"
                + "contentBoundary 必须精确说明哪些内容来自用户、允许怎样的直接扩展，以及不应扩展的边界。knowledgeGraph 未启用时，nodes 和 edges 必须为空数组。"
                + "categoryId 只可使用 frontend、backend、architecture、devops、career。sources 中仅保留实际检索到且被用于文章的来源。";
    }

    private String stripCodeFence(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) return trimmed.substring(firstNewline + 1, lastFence).trim();
        }
        return trimmed;
    }

    private String trimTrailingSlash(String value) {
        return value != null && value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /** Gateway failure carrying a stable HTTP status; used to classify transient upstream failures. */
    static class ModelGatewayException extends RuntimeException {
        private final int status;

        ModelGatewayException(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
