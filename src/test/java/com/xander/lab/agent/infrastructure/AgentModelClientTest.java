package com.xander.lab.agent.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.common.ModelCallResult;
import com.xander.lab.agent.domain.AgentAttachment;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentModelClientTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;
    private JsonNode requestPayload;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void streamsOnlyTheFinalReplyFieldAsUpstreamDeltasArrive() throws Exception {
        startServer(List.of(
                "{\"thought\":\"\",\"done\":true,\"reply\":\"你",
                "好",
                "，世界\",\"action\":null}"
        ));
        List<String> deltas = new ArrayList<>();

        JsonNode decision = client().decideNextStepStream("context", "tools", deltas::add).payload();

        assertThat(decision.path("done").asBoolean()).isTrue();
        assertThat(decision.path("reply").asText()).isEqualTo("你好，世界");
        assertThat(String.join("", deltas)).isEqualTo("你好，世界");
        assertThat(deltas).hasSizeGreaterThan(1);
    }

    @Test
    void sendsImagesAndDocumentsAsNativeResponsesInputs() throws Exception {
        startServer(List.of("附件已解析"));

        client().decideNextStepStream(
                "请分析附件", "",
                List.of(
                        new AgentAttachment("https://cdn.example.com/photo.png", "photo.png", "image/png", 12L),
                        new AgentAttachment("https://cdn.example.com/report.pdf", "report.pdf", "application/pdf", 34L)),
                ignored -> { }, () -> false);

        JsonNode content = requestPayload.path("input").path(0).path("content");
        assertThat(content.path(0).path("type").asText()).isEqualTo("input_text");
        assertThat(content.path(1).path("type").asText()).isEqualTo("input_image");
        assertThat(content.path(1).path("image_url").asText())
                .isEqualTo("https://cdn.example.com/photo.png");
        assertThat(content.path(2).path("type").asText()).isEqualTo("input_file");
        assertThat(content.path(2).path("file_url").asText())
                .isEqualTo("https://cdn.example.com/report.pdf");
    }

    @Test
    void asksTheModelToEmitTheReplyBeforeHiddenThought() throws Exception {
        startServer(List.of("{\"done\":true,\"reply\":\"你好\",\"thought\":\"\",\"action\":null}"));

        client().decideNextStep("context", "tools");

        String instructions = requestPayload.path("instructions").asText();
        assertThat(instructions)
                .contains("字段顺序必须严格为 done、reply、thought、action")
                .contains("thought 必须为空")
                .contains("不要在 reply 之前生成用户不可见的思考内容");
        assertThat(requestPayload.path("text").path("format").path("type").asText())
                .isEqualTo("json_schema");
        assertThat(requestPayload.path("text").path("format").path("name").asText())
                .isEqualTo("agent_decision");
        assertThat(requestPayload.path("text").path("format").path("strict").asBoolean())
                .isTrue();
        assertThat(requestPayload.path("text").path("format").path("schema")
                .path("required").toString())
                .isEqualTo("[\"done\",\"reply\",\"thought\",\"action\"]");
    }

    @Test
    void requestsLowReasoningEffortForLatencySensitiveGpt5AgentTurns() throws Exception {
        startServer(List.of("{\"done\":true,\"reply\":\"你好\",\"thought\":\"\",\"action\":null}"));

        client(false, "gpt-5").decideNextStep("context", "tools");

        assertThat(requestPayload.path("reasoning").path("effort").asText()).isEqualTo("low");
    }

    @Test
    void omitsReasoningEffortForNonReasoningCompatibilityModels() throws Exception {
        startServer(List.of("{\"done\":true,\"reply\":\"你好\",\"thought\":\"\",\"action\":null}"));

        client(false, "third-party-chat-model").decideNextStep("context", "tools");

        assertThat(requestPayload.has("reasoning")).isFalse();
    }

    @Test
    void keepsToolDecisionJsonOutOfTheUserFacingStream() throws Exception {
        startServer(List.of(
                "{\"thought\":\"需要生成博客\",\"done\":false,",
                "\"reply\":\"\",\"action\":{\"tool\":\"blog_generate\",",
                "\"args\":{\"input\":\"主题\"}}}"
        ));
        List<String> deltas = new ArrayList<>();

        JsonNode decision = client().decideNextStepStream("context", "tools", deltas::add).payload();

        assertThat(decision.path("done").asBoolean()).isFalse();
        assertThat(decision.path("action").path("tool").asText()).isEqualTo("blog_generate");
        assertThat(deltas).isEmpty();
    }

    @Test
    void decodesEscapedReplyPrefixesWithoutLeakingBrokenUnicode() {
        List<String> deltas = new ArrayList<>();
        AgentModelClient.DecisionReplyDecoder decoder =
                new AgentModelClient.DecisionReplyDecoder(deltas::add);

        decoder.accept("{\"thought\":\"\",\"done\":true,\"reply\":\"第一行\\n\\uD83D");
        decoder.accept("\\uDE80完成\",\"action\":null}");

        assertThat(String.join("", deltas)).isEqualTo("第一行\n🚀完成");
    }

    @Test
    void mapsUpstreamSseErrorsToASafeMessage() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/responses", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            var event = objectMapper.createObjectNode().put("type", "error");
            event.putObject("error").put("message", "secret vendor detail");
            writeEvent(exchange, event);
            exchange.close();
        });
        server.start();

        assertThatThrownBy(() -> client().decideNextStepStream("context", "tools", ignored -> { }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("模型服务暂时不可用，请稍后重试")
                .hasMessageNotContaining("secret vendor detail");
    }

    @Test
    void exposesWebSearchAndDescribesItWhenEnabled() throws Exception {
        startServer(List.of("{\"thought\":\"\",\"done\":true,\"reply\":\"已核验\",\"action\":null}"));

        client(true).decideNextStep("查找三个前端链接", "blog_generate: 生成博客");

        assertThat(requestPayload.path("tools").path(0).path("type").asText()).isEqualTo("web_search");
        assertThat(requestPayload.path("instructions").asText())
                .contains("平台已提供 web_search")
                .contains("不要声称平台不支持网页搜索");
    }

    @Test
    void exposesWebSearchEvenWhenTheTurnLooksLikePlainConversation() throws Exception {
        // 搜索能力不再按关键词开关：词表猜不中时模型会以为自己不能联网，于是凭记忆作答。
        startServer(List.of("{\"thought\":\"\",\"done\":true,\"reply\":\"好的\",\"action\":null}"));

        client(true).decideNextStep("随便聊聊", "blog_generate: 生成博客");

        assertThat(requestPayload.path("tools").path(0).path("type").asText()).isEqualTo("web_search");
    }

    @Test
    void omitsWebSearchWhenDisabledByConfiguration() throws Exception {
        startServer(List.of("{\"thought\":\"\",\"done\":true,\"reply\":\"好的\",\"action\":null}"));

        client(false).decideNextStep("查找三个前端链接", "blog_generate: 生成博客");

        assertThat(requestPayload.has("tools")).isFalse();
        assertThat(requestPayload.path("instructions").asText()).contains("网页搜索能力：当前未启用");
    }

    @Test
    void statesTheAgentPolicyWhenToolsAreAvailable() throws Exception {
        startServer(List.of("{\"thought\":\"\",\"done\":true,\"reply\":\"好的\",\"action\":null}"));

        client().decideNextStep("我有哪些需要复习的知识点", "query_knowledge: 查询知识");

        String instructions = requestPayload.path("instructions").asText();
        assertThat(instructions)
                .contains("个人知识智能体")
                .contains("plan_tasks")
                .contains("remember")
                .contains("绝不把失败说成完成")
                .contains("query_knowledge: 查询知识");
    }

    @Test
    void acceptsAValidDecisionWrappedInProviderCommentary() throws Exception {
        startServer(List.of(
                "下面是调度结果：\n```json\n",
                "{\"done\":false,\"reply\":\"\",\"thought\":\"\",",
                "\"action\":{\"tool\":\"image_generate\",\"args\":{\"prompt\":\"小猫\"}}}\n```"
        ));

        JsonNode decision = client().decideNextStep("生成图片：小猫", "tools").payload();

        assertThat(decision.path("done").asBoolean()).isFalse();
        assertThat(decision.path("action").path("tool").asText()).isEqualTo("image_generate");
        assertThat(decision.path("action").path("args").path("prompt").asText()).isEqualTo("小猫");
    }

    @Test
    void decodesStrictStructuredToolArgumentsBeforeReturningTheDecision() throws Exception {
        startServer(List.of(
                "{\"done\":false,\"reply\":\"\",\"thought\":\"\",",
                "\"action\":{\"tool\":\"image_generate\",",
                "\"args\":\"{\\\"prompt\\\":\\\"小猫\\\"}\"}}"
        ));

        JsonNode decision = client().decideNextStep("生成图片：小猫", "tools").payload();

        assertThat(decision.path("action").path("args").isObject()).isTrue();
        assertThat(decision.path("action").path("args").path("prompt").asText()).isEqualTo("小猫");
    }

    @Test
    void streamsPlainTextDirectlyForAnOrdinaryConversationTurn() throws Exception {
        startServer(List.of("SSE 是", "服务端推送协议"));
        List<String> deltas = new ArrayList<>();

        ModelCallResult call = client(true)
                .decideNextStepStream("解释一下 SSE", "", deltas::add);
        JsonNode decision = call.payload();

        // 没有业务工具时不再要求决策 JSON；web_search 由配置决定，与业务工具表无关。
        assertThat(requestPayload.has("text")).isFalse();
        assertThat(requestPayload.path("tools").path(0).path("type").asText()).isEqualTo("web_search");
        assertThat(requestPayload.path("instructions").asText())
                .contains("对话助手")
                .contains("本轮没有可用工具")
                .contains("不要输出 JSON")
                .doesNotContain("可调用工具：");
        assertThat(String.join("", deltas)).isEqualTo("SSE 是服务端推送协议");
        assertThat(decision.path("done").asBoolean()).isTrue();
        assertThat(decision.path("reply").asText()).isEqualTo("SSE 是服务端推送协议");
        assertThat(decision.path("action").isNull()).isTrue();
        assertThat(call.model()).isEqualTo("test-model");
    }

    @Test
    void omitsWebSearchAndDescribesItAsDisabledWhenTurnedOff() throws Exception {
        startServer(List.of("{\"thought\":\"\",\"done\":true,\"reply\":\"未搜索\",\"action\":null}"));

        client(false).decideNextStep("普通对话", "blog_generate: 生成博客");

        assertThat(requestPayload.has("tools")).isFalse();
        assertThat(requestPayload.path("instructions").asText()).contains("当前未启用");
    }

    private void startServer(List<String> outputDeltas) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/responses", exchange -> {
            requestPayload = objectMapper.readTree(exchange.getRequestBody());
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            for (String delta : outputDeltas) {
                writeEvent(exchange, objectMapper.createObjectNode()
                        .put("type", "response.output_text.delta")
                        .put("delta", delta));
            }
            writeEvent(exchange, objectMapper.createObjectNode().put("type", "response.completed"));
            exchange.close();
        });
        server.start();
    }

    private void writeEvent(HttpExchange exchange, JsonNode event) throws IOException {
        byte[] bytes = ("data: " + objectMapper.writeValueAsString(event) + "\n\n")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().flush();
    }

    private AgentModelClient client() {
        return client(false);
    }

    private AgentModelClient client(boolean webSearchEnabled) {
        return client(webSearchEnabled, "test-model");
    }

    private AgentModelClient client(boolean webSearchEnabled, String model) {
        AgentModelProperties properties = new AgentModelProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setApiKey("test-key");
        properties.setModel(model);
        properties.setWebSearchEnabled(webSearchEnabled);
        return new AgentModelClient(properties, objectMapper);
    }
}
