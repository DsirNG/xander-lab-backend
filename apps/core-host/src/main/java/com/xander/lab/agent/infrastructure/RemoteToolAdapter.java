package com.xander.lab.agent.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.agent.application.AgentPromptSection;
import com.xander.lab.agent.application.AgentTool;
import com.xander.lab.agent.spi.RemoteTool;
import com.xander.lab.agent.spi.RemoteToolCall;

import java.util.Optional;

/**
 * 把一个 {@link RemoteTool}（契约）翻成 {@link AgentTool}（agent 自己的工具类型）。
 *
 * <p>它是这一刀的<b>防腐层</b>：契约那一侧只认 JDK 类型，agent 这一侧的工具词汇表
 * （{@code AgentPromptSection}、{@code ProgressListener}、{@code JsonNode}）全在这里收口。
 * 没有它，integration 就得实现 {@code AgentTool}，而那是 {@code integration -> agent}
 * 这条跨部署单元实现依赖的来源。</p>
 *
 * <h2>为什么 Jackson 在这里</h2>
 * <p>契约把 schema、参数、返回值都当作 JSON 文本搬运（ADR-1：契约必须与序列化框架无关）。
 * 本类是入站适配器，把文本解析回 {@code JsonNode} 正是它的职责。schema 只解析一次并缓存：
 * 它是不变的，而 {@link #parametersSchema()} 每轮渲染工具面都会被调一次。</p>
 *
 * <h2>为什么准则固定挂副作用档</h2>
 * <p>契约只给准则正文，不给渲染顺序——顺序是渲染方的策略。远端工具的定义就是"调用会离开
 * 本平台"，所以它的准则天然属于 {@link AgentPromptSection#ORDER_SIDE_EFFECT}。把 order 放进
 * 契约的话，agent 的常量表就得跨模块再抄一份，两份值可以无声漂移。</p>
 */
final class RemoteToolAdapter implements AgentTool {

    private final RemoteTool remote;
    private final ObjectMapper objectMapper;

    /** 解析一次的 schema；{@code null} 表示还没解析过。 */
    private volatile JsonNode schema;

    RemoteToolAdapter(RemoteTool remote, ObjectMapper objectMapper) {
        this.remote = remote;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return remote.name();
    }

    @Override
    public String description() {
        return remote.description();
    }

    @Override
    public JsonNode parametersSchema() {
        JsonNode current = schema;
        if (current != null) return current;
        JsonNode parsed = parseSchema(remote.parametersSchemaJson());
        schema = parsed;
        return parsed;
    }

    @Override
    public Optional<AgentPromptSection> promptSection() {
        String text = remote.promptText();
        if (text == null || text.isBlank()) return Optional.empty();
        return Optional.of(AgentPromptSection.sideEffect(text));
    }

    @Override
    public Optional<String> approvalReason(JsonNode args) {
        String reason = remote.approvalReason(args == null ? null : args.toString());
        if (reason == null || reason.isBlank()) return Optional.empty();
        return Optional.of(reason);
    }

    @Override
    public boolean readOnly() {
        return remote.readOnly();
    }

    @Override
    public JsonNode execute(Long userId, JsonNode args, ProgressListener onProgress) {
        String result = remote.invoke(userId, args == null ? null : args.toString(),
                new ProgressBridge(onProgress));
        return parseResult(result);
    }

    /**
     * schema 坏了就退化成空对象 schema，<b>不</b>抛异常。
     *
     * <p>工具面每一轮都要渲染，一个坏 schema 抛出去等于把整轮对话永久打挂，而它只是一条配置
     * 数据的问题——这与 {@code McpToolSource#readCatalog} 对损坏目录的处理是同一条判断
     * （"少几个工具，对话照常"）。</p>
     *
     * <p>正常路径上走不到这里：{@code RemoteMcpTool} 的 schema 来自一个已解析的
     * {@code JsonNode}，{@code toString()} 出来的必然合法。这个分支是为将来的其他提供方留的。</p>
     */
    private JsonNode parseSchema(String json) {
        if (json != null && !json.isBlank()) {
            try {
                JsonNode parsed = objectMapper.readTree(json);
                if (parsed != null && parsed.isObject()) return parsed;
            } catch (JsonProcessingException ignored) {
                // 落到下面返回空对象 schema。
            }
        }
        return emptyObjectSchema();
    }

    /**
     * 返回值必须是合法 JSON 文本，空或空白是违约。
     *
     * <p>这里<b>不</b>像 schema 那样兜底：调用方（编排循环）要拿这个结果去拼下一轮请求，
     * 一个"没有结果"的结果会被当成模型看到的真实返回。抛出去会变成一次工具调用失败，
     * 模型看得见原因——比静默返回一个空对象诚实。契约已经在 {@link RemoteTool#invoke}
     * 上写明了这条要求。</p>
     */
    private JsonNode parseResult(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalStateException(
                    "远端工具 " + remote.name() + " 违反了契约：invoke 返回了空结果");
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "远端工具 " + remote.name() + " 返回的不是合法 JSON", exception);
        }
    }

    private static ObjectNode emptyObjectSchema() {
        ObjectNode empty = JsonNodeFactory.instance.objectNode();
        empty.put("type", "object");
        empty.set("properties", JsonNodeFactory.instance.objectNode());
        return empty;
    }

    /** 把契约的回传通道转成编排层认识的 {@link ProgressListener}。 */
    private record ProgressBridge(ProgressListener listener) implements RemoteToolCall {

        @Override
        public void onProgress(String message) {
            listener.onProgress(message);
        }

        @Override
        public void checkCancellation() {
            listener.checkCancellation();
        }
    }
}
