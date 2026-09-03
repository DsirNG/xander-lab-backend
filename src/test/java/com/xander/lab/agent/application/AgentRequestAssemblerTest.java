package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xander.lab.agent.domain.AgentAttachment;
import com.xander.lab.agent.domain.AgentMessage;
import com.xander.lab.agent.domain.AgentPlanItem;
import com.xander.lab.agent.infrastructure.AgentConversationProperties;
import com.xander.lab.agent.infrastructure.AgentModelProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Guards one-source request rendering and durable reference snapshots. */
class AgentRequestAssemblerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void rendersTheModelRequestAndJournalSnapshotFromTheSameWindow() throws Exception {
        AgentConversationProperties conversationProperties = mock(AgentConversationProperties.class);
        when(conversationProperties.getContextMessageLimit()).thenReturn(5);
        AgentModelProperties modelProperties = new AgentModelProperties();
        modelProperties.setModel("agent-test-model");
        AgentPlanService planService = mock(AgentPlanService.class);
        when(planService.render(List.of(new AgentPlanItem("收集资料", "PENDING", ""))))
                .thenReturn("1. [PENDING] 收集资料");
        AgentRequestAssembler assembler = new AgentRequestAssembler(
                new AgentToolRegistry(List.of(tool("query_posts"), tool("publish_post"))),
                conversationProperties, modelProperties, planService, new AgentQuizService(objectMapper), objectMapper);
        List<AgentMessage> messages = List.of(
                message(1L, "user", "message", "first message outside window", List.of()),
                message(2L, "user", "message", "private draft request", List.of(
                        new AgentAttachment("https://cdn.example.com/a.png", "a.png", "image/png", 12))),
                message(3L, "assistant", "answer", "working on it", List.of()),
                message(4L, "tool", "tool_result", "{\"ok\":true}", List.of()),
                message(5L, "assistant", "thought", "next action", List.of()),
                message(6L, "user", "message", "continue", List.of()));

        AgentStepRequest request = assembler.assemble(new AgentRequestAssemblyInput(
                7L, 42L, 3L, 2, messages, List.of(new AgentPlanItem("收集资料", "PENDING", "")),
                "用户喜欢简洁答案", null, null, Set.of("publish_post", "query_posts"), 4, false));

        assertThat(request.context()).contains("private draft request").doesNotContain("first message outside window");
        assertThat(request.attachments()).extracting(AgentAttachment::url)
                .containsExactly("https://cdn.example.com/a.png");
        assertThat(request.contextMessageIds()).containsExactly(2L, 3L, 4L, 5L, 6L);
        assertThat(request.toolNames()).containsExactly("publish_post", "query_posts");
        assertThat(request.toolSchemaSetHash()).hasSize(64);
        assertThat(request.journalSnapshotJson()).doesNotContain("private draft request")
                .contains("contextMessageIds").contains("agent-test-model");
        var snapshot = objectMapper.readTree(request.journalSnapshotJson());
        assertThat(snapshot.path("schemaVersion").asInt()).isEqualTo(2);
        assertThat(snapshot.path("attachments").get(0).path("url").asText())
                .isEqualTo("https://cdn.example.com/a.png");
    }

    @Test
    void instructsTheModelToFollowTheCurrentUserLanguageAndDefaultToChineseWhenAmbiguous() {
        AgentConversationProperties conversationProperties = mock(AgentConversationProperties.class);
        when(conversationProperties.getContextMessageLimit()).thenReturn(5);
        AgentRequestAssembler assembler = new AgentRequestAssembler(
                new AgentToolRegistry(List.of(tool("query_posts"))), conversationProperties,
                new AgentModelProperties(), mock(AgentPlanService.class), new AgentQuizService(objectMapper), objectMapper);

        AgentStepRequest request = assembler.assemble(new AgentRequestAssemblyInput(
                7L, 42L, 3L, 1, List.of(message(1L, "user", "message", "Explain this in English", List.of())),
                List.of(), "", null, null, Set.of("query_posts"), 4, false));

        assertThat(request.context())
                .contains("使用用户当前诉求所使用的自然语言回复")
                .contains("默认使用中文")
                .contains("不要因为系统说明或工具结果是中文而改变回复语言");
        assertThat(request.journalSnapshotJson())
                .contains("conversation-v2-language-policy")
                .contains("current-user-language-or-zh-when-ambiguous");
    }

    /**
     * 前缀缓存按字节前缀命中，所以 context 的顺序是"只追加的在前、一轮之内会变的在后"。
     *
     * <p>回归：早先长期记忆和任务计划渲染在最前面，模型中途改一次计划就把整段对话记录后移，
     * 这一轮剩下每一步都得从头算一遍提示词。</p>
     */
    @Test
    void volatileSessionStateIsRenderedAfterTheAppendOnlyTranscript() {
        AgentRequestAssembler assembler = assembler("1. [PENDING] 收集资料");
        List<AgentMessage> messages = List.of(
                message(1L, "user", "message", "帮我整理这批笔记", List.of()),
                message(2L, "tool", "tool_result", "{\"ok\":true}", List.of()));

        String context = assembler.assemble(new AgentRequestAssemblyInput(
                7L, 42L, 3L, 2, messages, List.of(new AgentPlanItem("收集资料", "PENDING", "")),
                "用户喜欢简洁答案", JsonNodeFactory.instance.objectNode().put("error", "超时"),
                "结论缺少工具结果支撑", Set.of("query_posts"), 4, false)).context();

        assertThat(context.indexOf("帮我整理这批笔记"))
                .isLessThan(context.indexOf("用户喜欢简洁答案"));
        assertThat(context.indexOf("用户喜欢简洁答案"))
                .isLessThan(context.indexOf("1. [PENDING] 收集资料"));
        assertThat(context.indexOf("1. [PENDING] 收集资料"))
                .isLessThan(context.indexOf("【最近一次工具失败】"));
        assertThat(context.indexOf("【最近一次工具失败】"))
                .isLessThan(context.indexOf("【自检结果】"));
    }

    /** 同一轮里改写任务计划，不应该动到它前面那一大段对话记录的字节。 */
    @Test
    void rewritingThePlanMidRunLeavesTheTranscriptPrefixByteIdentical() {
        List<AgentMessage> messages = List.of(
                message(1L, "user", "message", "帮我整理这批笔记", List.of()),
                message(2L, "tool", "tool_result", "{\"ok\":true}", List.of()));

        String before = assembler("1. [PENDING] 收集资料").assemble(input(messages, "收集资料", "PENDING")).context();
        String after = assembler("1. [DONE] 收集资料").assemble(input(messages, "收集资料", "DONE")).context();

        String transcript = before.substring(0, before.indexOf("以下是你自己写下的任务计划"));
        assertThat(transcript).contains("帮我整理这批笔记");
        assertThat(after).startsWith(transcript);
    }

    /** 追加一条消息只应该把上一步的对话记录变长，而不是重排它。 */
    @Test
    void appendingAMessageOnlyExtendsThePreviousTranscript() {
        AgentRequestAssembler assembler = assembler("");
        AgentMessage first = message(1L, "user", "message", "帮我整理这批笔记", List.of());
        AgentMessage second = message(2L, "assistant", "answer", "已整理好", List.of());

        String step1 = assembler.assemble(input(List.of(first), null, null)).context();
        String step2 = assembler.assemble(input(List.of(first, second), null, null)).context();

        String transcript = step1.substring(0, step1.indexOf("以下是你对这位用户的长期记忆"));
        assertThat(step2).startsWith(transcript);
        assertThat(step2).contains("已整理好");
    }

    /**
     * 压缩检查点是对话记录的地板：它之前的消息不再进入模型上下文。
     *
     * <p>装配层每一步都自己按这条地板裁一次，所以重启续跑、审批恢复这些
     * 不经过压缩调用的路径也照同一条地板走——否则刚换掉的那一段会被重新读回来，
     * 压缩等于没发生。</p>
     */
    @Test
    void messagesBeforeTheCompactionCheckpointNeverEnterTheContext() {
        AgentRequestAssembler assembler = assembler("");
        List<AgentMessage> messages = List.of(
                message(1L, "user", "message", "很早以前定下的目标", List.of()),
                message(2L, "assistant", "answer", "很早以前的回复", List.of()),
                message(3L, "user", AgentCompactionService.CHECKPOINT_KIND, "检查点：目标是整理笔记", List.of()),
                message(4L, "user", "message", "继续", List.of()));

        AgentStepRequest request = assembler.assemble(input(messages, null, null));

        assertThat(request.context())
                .contains("检查点：目标是整理笔记")
                .contains("继续")
                .doesNotContain("很早以前定下的目标")
                .doesNotContain("很早以前的回复");
        assertThat(request.contextMessageIds()).containsExactly(3L, 4L);
    }

    /** 条数窗口不能把刚花钱换来的检查点挤掉：它固定留在最前面，剩下的额度给最新的几条。 */
    @Test
    void theCountWindowPinsTheCheckpointInsteadOfDroppingIt() {
        AgentRequestAssembler assembler = assembler("", 5);
        List<AgentMessage> messages = new java.util.ArrayList<>();
        messages.add(message(1L, "user", AgentCompactionService.CHECKPOINT_KIND, "检查点：目标是整理笔记", List.of()));
        for (long id = 2L; id <= 9L; id++) {
            messages.add(message(id, "user", "message", "第" + id + "条", List.of()));
        }

        AgentStepRequest request = assembler.assemble(input(messages, null, null));

        assertThat(request.contextMessageIds()).containsExactly(1L, 6L, 7L, 8L, 9L);
        assertThat(request.context()).contains("检查点：目标是整理笔记").doesNotContain("第5条");
    }

    private AgentRequestAssembler assembler(String renderedPlan) {
        return assembler(renderedPlan, 30);
    }

    private AgentRequestAssembler assembler(String renderedPlan, int contextMessageLimit) {
        AgentConversationProperties conversationProperties = mock(AgentConversationProperties.class);
        when(conversationProperties.getContextMessageLimit()).thenReturn(contextMessageLimit);
        AgentPlanService planService = mock(AgentPlanService.class);
        when(planService.render(org.mockito.ArgumentMatchers.anyList())).thenReturn(renderedPlan);
        return new AgentRequestAssembler(new AgentToolRegistry(List.of(tool("query_posts"))),
                conversationProperties, new AgentModelProperties(), planService,
                new AgentQuizService(objectMapper), objectMapper);
    }

    private AgentRequestAssemblyInput input(List<AgentMessage> messages, String planTitle, String planStatus) {
        List<AgentPlanItem> plan = planTitle == null ? List.of()
                : List.of(new AgentPlanItem(planTitle, planStatus, ""));
        return new AgentRequestAssemblyInput(7L, 42L, 3L, 2, messages, plan,
                "用户喜欢简洁答案", null, null, Set.of("query_posts"), 4, false);
    }

    private AgentMessage message(Long id, String role, String kind, String content,
                                 List<AgentAttachment> attachments) {
        AgentMessage message = new AgentMessage();
        message.setId(id);
        message.setRole(role);
        message.setKind(kind);
        message.setContent(content);
        message.setAttachments(attachments);
        return message;
    }

    private AgentTool tool(String name) {
        return new AgentTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return name + " description";
            }

            @Override
            public com.fasterxml.jackson.databind.JsonNode parametersSchema() {
                return JsonNodeFactory.instance.objectNode().put("type", "object");
            }

            @Override
            public com.fasterxml.jackson.databind.JsonNode execute(Long userId,
                                                                   com.fasterxml.jackson.databind.JsonNode args,
                                                                   ProgressListener onProgress) {
                return JsonNodeFactory.instance.objectNode();
            }
        };
    }
}
