package com.xander.lab.agent.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.admin.application.ModelGateway;
import com.xander.lab.admin.application.ModelGateway.ModelEndpoint;
import com.xander.lab.common.ModelCallResult;
import com.xander.lab.agent.application.AgentToolRegistry;
import com.xander.lab.agent.application.EmitArtifactTool;
import com.xander.lab.agent.application.EmitQuizTool;
import com.xander.lab.agent.application.GradeAnswerTool;
import com.xander.lab.agent.domain.AgentAttachment;
import com.xander.lab.agent.domain.AgentInputTurn;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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
                List.of(AgentInputTurn.user("请分析附件")), "",
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

    /**
     * 对话记录按 role 发出去，而不是压成一段"【用户】…【智能体】…"的纯文本。
     *
     * <p>压成一段时模型看到的全都是用户说的话，包括它自己上一轮的回复；
     * 谁说的只能靠正文里的方括号标记去猜，而那些标记用户自己也能打出来。</p>
     */
    @Test
    void sendsTheTranscriptAsRoleStructuredInputMessages() throws Exception {
        startServer(List.of("{\"done\":true,\"reply\":\"好的\",\"thought\":\"\",\"action\":null}"));

        client().decideNextStepStream(
                List.of(AgentInputTurn.user("帮我整理这批笔记"),
                        AgentInputTurn.assistant("（回复：）已经整理好了"),
                        AgentInputTurn.user("【工具结果】{\"ok\":true}")),
                "query_posts: 查文章", ignored -> { }, () -> false);

        JsonNode input = requestPayload.path("input");
        assertThat(input.isArray()).isTrue();
        assertThat(input).hasSize(3);
        assertThat(input.path(0).path("role").asText()).isEqualTo("user");
        assertThat(input.path(0).path("content").asText()).isEqualTo("帮我整理这批笔记");
        assertThat(input.path(1).path("role").asText()).isEqualTo("assistant");
        assertThat(input.path(2).path("role").asText()).isEqualTo("user");
        assertThat(input.path(2).path("content").asText()).contains("【工具结果】");
    }

    /** 附件属于"当前这次诉求"，所以挂在最后一轮 user 上，而不是模型自己说的话上。 */
    @Test
    void attachesFilesToTheLastUserTurnRatherThanTheFirst() throws Exception {
        startServer(List.of("{\"done\":true,\"reply\":\"好的\",\"thought\":\"\",\"action\":null}"));

        client().decideNextStepStream(
                List.of(AgentInputTurn.user("第一轮"),
                        AgentInputTurn.assistant("中间回复"),
                        AgentInputTurn.user("看看这张图")),
                "query_posts: 查文章",
                List.of(new AgentAttachment("https://cdn.example.com/photo.png", "photo.png", "image/png", 12L)),
                ignored -> { }, () -> false);

        JsonNode input = requestPayload.path("input");
        assertThat(input.path(0).path("content").asText()).isEqualTo("第一轮");
        assertThat(input.path(2).path("content").path(0).path("text").asText()).isEqualTo("看看这张图");
        assertThat(input.path(2).path("content").path(1).path("type").asText()).isEqualTo("input_image");
    }

    @Test
    void asksTheModelToEmitTheReplyBeforeHiddenThought() throws Exception {
        startServer(List.of("{\"done\":true,\"reply\":\"你好\",\"thought\":\"\",\"action\":null}"));

        client().decideNextStep("context", "tools");

        String instructions = requestPayload.path("instructions").asText();
        assertThat(instructions)
                .contains("字段顺序必须严格为 done、reply、thought、action、actions")
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
                .isEqualTo("[\"done\",\"reply\",\"thought\",\"action\",\"actions\"]");
    }

    /**
     * thought 每步都会实时推给用户，所以协议里它是"一句进度"，不是模型的思维链。
     *
     * <p>回归：用户发"给我一个 sse 代码"，界面上出现的是模型的原始推理，还带着内部工具契约。
     * 当时协议只说 thought 是"本步的简要思考"——模型照做了，是这句定义写错了。</p>
     */
    @Test
    void definesThoughtAsAUserFacingProgressLineRatherThanChainOfThought() throws Exception {
        startServer(List.of("{\"done\":true,\"reply\":\"你好\",\"thought\":\"\",\"action\":null}"));

        client().decideNextStep("context", "tools");

        assertThat(requestPayload.path("instructions").asText())
                .contains("thought 是直接显示给用户的一句进度说明，不是你的内部推理")
                .contains("不要写工具名、参数、字段名")
                .contains("真正的推理留在你自己的思考里")
                .doesNotContain("本步的简要思考");
    }

    @Test
    void requiresStructuredQuizCardsInsteadOfMarkdownQuestions() throws Exception {
        startServer(List.of("{\"done\":true,\"reply\":\"请作答\",\"thought\":\"\",\"action\":null}"));

        client().decideNextStep("请出三道选择题", toolSurface("emit_quiz", "grade_answer"));

        assertThat(requestPayload.path("instructions").asText())
                .contains("必须调用 emit_quiz 下发结构化答题卡")
                .contains("禁止把题目、选项或逐题作答要求写进 reply")
                .contains("答题卡支持自由翻阅并一次性提交全部答案")
                .contains("$\\frac{3}{5}$")
                .contains("$\\frac{\\sqrt{3}}{2}$")
                .contains("先调用 grade_answer 完成逐题判分")
                .contains("如果你愿意，我也可以为你出一组纠错练习")
                .contains("不要自动执行该动作");
    }

    /**
     * 代码交付形式的判据必须是"用户拿到之后要干什么"，而不是"有几个文件"。
     *
     * <p>回归：早先这条准则写的是"用户要的是可运行的代码、示例、项目或脚本时……必须调用
     * emit_artifact"，唯一的例外是"两三行的小片段"。于是一句"给我一个 sse 代码"必然被开成交付卡
     * ——SSE 演示写不到三行——用户想要的只是一段能扫一眼的片段。</p>
     */
    @Test
    void prefersInlineCodeFencesOverForcingAnArtifactCard() throws Exception {
        startServer(List.of("{\"done\":true,\"reply\":\"给你一段\",\"thought\":\"\",\"action\":null}"));

        client().decideNextStep("给我一个sse代码", toolSurface("emit_artifact"));

        assertThat(requestPayload.path("instructions").asText())
                .contains("默认写在 reply 的代码围栏里，不要一律开交付卡")
                .contains("判据是用户拿到之后要干什么，而不是有几个文件")
                .contains("“给我一个 SSE 的例子”“sse 代码怎么写”用围栏")
                .contains("拿不准就先用围栏")
                .contains("代码必须真的出现在这一轮里")
                .doesNotContain("必须调用它把每个文件的完整源码交付出去");
    }

    /**
     * 只有本轮真在工具表里的工具，它的准则才进提示词。
     *
     * <p>否则就是为模型根本调不到的工具规定行为：出题准则会跟着一句"给我一个 sse 代码"一起下发，
     * 模型收到"必须下发答题卡"却没有 emit_quiz 可调。</p>
     */
    @Test
    void doesNotStatePoliciesForToolsMissingFromThisTurnsTable() throws Exception {
        startServer(List.of("{\"done\":true,\"reply\":\"给你一段\",\"thought\":\"\",\"action\":null}"));

        client().decideNextStep("给我一个sse代码", toolSurface("emit_artifact"));

        String instructions = requestPayload.path("instructions").asText();
        assertThat(instructions)
                .contains("工具使用准则")
                .doesNotContain("必须调用 emit_quiz 下发结构化答题卡")
                .doesNotContain("先调用 grade_answer 完成逐题判分");
        // 两份准则同时在场时模型要知道谁说了算：工具准则是针对本轮真实工具表写的。
        assertThat(instructions).contains("以工具使用准则为准");
    }

    @Test
    void requestsLowReasoningEffortForLatencySensitiveGpt5AgentTurns() throws Exception {
        startServer(List.of("{\"done\":true,\"reply\":\"你好\",\"thought\":\"\",\"action\":null}"));

        client(false, "gpt-5").decideNextStep("context", "tools");

        assertThat(requestPayload.path("reasoning").path("effort").asText()).isEqualTo("low");
    }

    /**
     * grok 族群必须也收到 effort。
     *
     * <p>生产主供应商就是 grok-4.5。原先只认 gpt-5 / o 系，grok 被判成"不支持"而漏传，
     * 端点于是退回自己的默认 {@code high}：实测同一句 "hi" 的 reasoning_tokens 从 566
     * 掉到 180 只靠这一个参数。漏传时是静默的——请求照样成功，只是每次都贵且重。</p>
     */
    @Test
    void requestsLowReasoningEffortForGrokAgentTurns() throws Exception {
        startServer(List.of("{\"done\":true,\"reply\":\"你好\",\"thought\":\"\",\"action\":null}"));

        client(false, "grok-4.5").decideNextStep("context", "tools");

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
    void treatsAToolStepWithoutAToolNameAsHandingTheTurnBackToTheUser() throws Exception {
        // 出完题等用户作答时模型常常这样输出：它没做完，但也没有工具可调。
        // reply 已经流式推给用户了，按收口处理，而不是让整轮报错。
        startServer(List.of(
                "{\"done\":false,\"reply\":\"第 1 题：为什么不能把或者理解成两者同时成立？\",",
                "\"thought\":\"等用户作答后再判分\",\"action\":null}"
        ));
        List<String> deltas = new ArrayList<>();

        JsonNode decision = client().decideNextStepStream("context", "tools", deltas::add).payload();

        assertThat(decision.path("done").asBoolean()).isTrue();
        assertThat(decision.path("reply").asText()).startsWith("第 1 题：");
        assertThat(decision.path("thought").asText()).isEmpty();
        assertThat(decision.path("action").isNull()).isTrue();
        assertThat(String.join("", deltas)).isEqualTo(decision.path("reply").asText());
    }

    @Test
    void leavesAToolStepWithNeitherToolNameNorReplyToTheOrchestrator() throws Exception {
        // 什么都没决定才是真的协议错误：这里不抛断整轮，交给编排循环的失败预算回灌纠正。
        startServer(List.of("{\"done\":false,\"reply\":\"\",\"thought\":\"\",\"action\":null}"));

        JsonNode decision = client().decideNextStep("context", "tools").payload();

        assertThat(decision.path("done").asBoolean()).isFalse();
        assertThat(decision.path("action").path("tool").asText()).isEmpty();
    }

    /**
     * 回归：{@code done=false} + 只有 {@code actions} 时，那个"没工具可调就按收口处理"的兜底
     * 会把整批调用悄悄丢掉——模型报了三件事，用户只收到一句"已完成"。
     */
    @Test
    void doesNotHandBackAToolStepThatCarriedABatchOfActions() throws Exception {
        startServer(List.of(
                "{\"done\":false,\"reply\":\"我同时查一下你的知识点、文章和计划\",\"thought\":\"并行查询\",",
                "\"action\":null,\"actions\":[",
                "{\"tool\":\"query_knowledge\",\"args\":\"{}\"},",
                "{\"tool\":\"query_posts\",\"args\":\"{}\"}]}"
        ));

        JsonNode decision = client().decideNextStep("context", "tools").payload();

        assertThat(decision.path("done").asBoolean()).isFalse();
        assertThat(decision.path("actions")).hasSize(2);
        assertThat(decision.path("actions").get(0).path("tool").asText()).isEqualTo("query_knowledge");
        // 数组里的 args 也要被解码成对象：漏掉它，执行管道会以"参数必须是 JSON 对象"整批拒绝，
        // 而模型按协议写得完全正确。
        assertThat(decision.path("actions").get(0).path("args").isObject()).isTrue();
        assertThat(decision.path("actions").get(1).path("args").isObject()).isTrue();
    }

    /**
     * {@code action} 与 {@code actions[]} 的元素必须是逐字段相同的定义。
     * 两边各写一遍迟早漂移，而漂移的表现是模型按 A 形状发、服务端按 B 形状解。
     */
    @Test
    void declaresActionsInTheStrictSchemaWithTheSameElementShapeAsAction() throws Exception {
        startServer(List.of("{\"done\":true,\"reply\":\"你好\",\"thought\":\"\",\"action\":null,\"actions\":null}"));

        client().decideNextStep("context", "tools");

        JsonNode schema = requestPayload.path("text").path("format").path("schema");
        JsonNode single = schema.path("properties").path("action").path("anyOf").get(0);
        JsonNode element = schema.path("properties").path("actions").path("anyOf").get(0).path("items");

        assertThat(element).isEqualTo(single);
        assertThat(schema.path("properties").path("actions").path("anyOf").get(1).path("type").asText())
                .isEqualTo("null");
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
                .contains("交还给用户")
                .contains("不要返回 done=false 而 action 为空")
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

    /**
     * 管理台把接口风格配成 CHAT_COMPLETIONS 时，请求必须真的打到 /chat/completions。
     *
     * <p>此前这个字段完全不被读取：无论配成什么，请求都发往 /responses。配置看似生效
     * 实则被忽略，比没有这个配置更糟——运维改完以为切了协议，实际毫无变化。</p>
     */
    @Test
    void usesChatCompletionsWhenTheEndpointSaysSo() throws Exception {
        List<String> paths = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            requestPayload = objectMapper.readTree(exchange.getRequestBody());
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            // Chat Completions 的帧没有 type，增量在 choices[0].delta.content。
            var chunk = objectMapper.createObjectNode();
            chunk.putArray("choices").addObject().putObject("delta")
                    .put("content", "{\"done\":true,\"reply\":\"走了聊天协议\",\"thought\":\"\",\"action\":null}");
            writeEvent(exchange, chunk);
            var last = objectMapper.createObjectNode();
            last.putArray("choices").addObject().put("finish_reason", "stop");
            last.putObject("usage").put("prompt_tokens", 11).put("completion_tokens", 7);
            writeEvent(exchange, last);
            exchange.close();
        });
        server.start();

        JsonNode decision = chatCompletionsClient().decideNextStep("你好", "tools").payload();

        assertThat(paths).containsExactly("/chat/completions");
        assertThat(decision.path("reply").asText()).isEqualTo("走了聊天协议");
        // 协议换了，body 形态也得换：system 消息 + messages 数组，而不是 instructions/input。
        assertThat(requestPayload.path("messages").path(0).path("role").asText()).isEqualTo("system");
        assertThat(requestPayload.has("instructions")).isFalse();
        assertThat(requestPayload.has("input")).isFalse();
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

    /** 网关把接口风格指到 CHAT_COMPLETIONS 的客户端：验证协议分流真的生效。 */
    private AgentModelClient chatCompletionsClient() {
        AgentModelProperties properties = new AgentModelProperties();
        properties.setWebSearchEnabled(false);
        ModelEndpoint endpoint = new ModelEndpoint(6L, "http://127.0.0.1:" + server.getAddress().getPort(),
                "test-key", "grok-4.5", ModelGateway.API_STYLE_CHAT_COMPLETIONS);
        return new AgentModelClient(properties, objectMapper, featureKey -> List.of(endpoint));
    }

    /**
     * 按生产路径渲染本轮的工具面：编排层交给模型的 toolDescriptions 就是这个字符串。
     *
     * <p>出题、判分、代码交付这些准则挂在各自的工具上（见 {@code AgentPromptSection}），
     * 只有工具真在本轮工具表里，准则才会进提示词。这里若手写一段 toolDescriptions 字面量，
     * 断言的就不再是模型真正收到的内容——准则从全局行动准则搬到工具上时不会有任何测试报警。</p>
     */
    private String toolSurface(String... names) {
        AgentToolRegistry registry = new AgentToolRegistry(
                List.of(new EmitQuizTool(), new GradeAnswerTool(null), new EmitArtifactTool()));
        return registry.describeToolSurface(new LinkedHashSet<>(List.of(names)));
    }
}
