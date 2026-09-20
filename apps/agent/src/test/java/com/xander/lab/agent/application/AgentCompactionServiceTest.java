package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.agent.domain.AgentInputTurn;
import com.xander.lab.agent.domain.AgentMessage;
import com.xander.lab.agent.infrastructure.AgentConversationProperties;
import com.xander.lab.agent.infrastructure.AgentModelClient;
import com.xander.lab.common.ModelCallResult;
import com.xander.lab.common.ModelUsage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 上下文压缩的行为边界。
 *
 * <p>这些用例守的是压缩最容易失效的几种方式：永远不触发、每轮都触发、
 * 把摘要换成一段更长的复述、切开工具调用与它的结果、以及自己出错时把用户这一轮也搭进去。</p>
 */
class AgentCompactionServiceTest {

    private static final String TOOL_SURFACE = "query_posts: 查询文章";

    private final AgentModelClient modelClient = mock(AgentModelClient.class);
    private final AgentEventProjector eventProjector = mock(AgentEventProjector.class);
    private final AgentConversationProperties properties = new AgentConversationProperties();
    private final AgentCompactionService service =
            new AgentCompactionService(modelClient, properties, eventProjector);

    @Test
    void disabledCompactionFallsBackToTheOldWindowOnlyBehaviour() {
        properties.setCompactionEnabled(false);
        properties.setContextMessageLimit(10);
        List<AgentMessage> messages = longMessages(12);

        AgentCompactionService.Compacted compacted = compact(messages);

        assertThat(compacted.messages()).isEqualTo(messages);
        assertThat(compacted.usage()).isEqualTo(ModelUsage.NONE);
        verifyNoInteractions(modelClient);
        verifyNoInteractions(eventProjector);
    }

    @Test
    void leavesTheTranscriptAloneWhileBothTriggersAreQuiet() {
        properties.setContextMessageLimit(10);
        List<AgentMessage> messages = longMessages(6);

        AgentCompactionService.Compacted compacted = compact(messages);

        assertThat(compacted.messages()).isEqualTo(messages);
        assertThat(compacted.model()).isNull();
        verifyNoInteractions(modelClient);
        verifyNoInteractions(eventProjector);
    }

    /**
     * 条数触发：窗口要丢东西的那一刻就是该压缩的那一刻。
     *
     * <p>回归：只按 token 压力触发的话，{@code contextMessageLimit} 会先把旧消息静默丢掉，
     * 压力永远到不了阈值，压缩一次也不会发生——那正是"有窗口没压缩"这个 bug 的形状。</p>
     */
    @Test
    void theCountTriggerFiresBeforeTheWindowSilentlyDropsOldMessages() {
        properties.setContextMessageLimit(10);
        stubSummary("检查点：用户要整理笔记", new ModelUsage(1200, 800, 60));
        List<AgentMessage> messages = longMessages(12);

        AgentCompactionService.Compacted compacted = compact(messages);

        // 尾部压到半个窗口（5 条）留出回差，头部 7 条换成一条检查点。
        assertThat(compacted.messages()).hasSize(6);
        assertThat(compacted.messages().get(0).getKind())
                .isEqualTo(AgentCompactionService.CHECKPOINT_KIND);
        assertThat(compacted.messages().get(0).getContent()).contains("检查点：用户要整理笔记");
        assertThat(compacted.messages().subList(1, 6)).isEqualTo(messages.subList(7, 12));
        assertThat(compacted.usage()).isEqualTo(new ModelUsage(1200, 800, 60));
    }

    /**
     * 检查点必须落库：它是对话记录的新地板。
     *
     * <p>只活在内存里的话，下一步重新读库就把刚换掉的那一段整段读回来，压缩等于没发生。</p>
     */
    @Test
    void persistsTheCheckpointSoTheNextStepReadsTheSameFloorFromTheDatabase() {
        properties.setContextMessageLimit(10);
        stubSummary("检查点：用户要整理笔记", ModelUsage.NONE);

        compact(longMessages(12));

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(eventProjector).saveActiveMessage(eq(7L), eq(42L), eq(3L),
                eq(AgentInputTurn.USER), eq(AgentCompactionService.CHECKPOINT_KIND),
                eq(null), content.capture());
        assertThat(content.getValue())
                .contains(AgentCompactionService.SUMMARY_OPEN_TAG)
                .contains("检查点：用户要整理笔记")
                .contains(AgentCompactionService.SUMMARY_CLOSE_TAG)
                // 检查点是既成事实的背景，不是一件需要向用户交代的事。
                .contains("不要向用户复述它");
    }

    /** token 触发：少量消息也可能个个巨大，这一条防的是真正的窗口溢出。 */
    @Test
    void theTokenPressureTriggerFiresEvenWhenTheMessageCountIsSmall() {
        properties.setContextWindowTokens(4096);
        properties.setContextMessageLimit(10);
        stubSummary("检查点：只剩收尾", ModelUsage.NONE);
        List<AgentMessage> messages = messages(6, 350);

        AgentCompactionService.Compacted compacted = compact(messages);

        assertThat(compacted.messages()).hasSize(2);
        assertThat(compacted.messages().get(0).getKind())
                .isEqualTo(AgentCompactionService.CHECKPOINT_KIND);
        assertThat(compacted.messages().get(1)).isSameAs(messages.get(5));
    }

    /**
     * 压力全在尾部时压缩解决不了问题，别白烧一次调用。
     *
     * <p>一条巨大的工具结果就能把保留预算吃光，此时可压缩的头部不足，
     * 该由装配层原有的单条截断去处理。</p>
     */
    @Test
    void skipsCompactionWhenTheTailAloneFillsTheRetainBudget() {
        properties.setContextWindowTokens(4096);
        properties.setContextMessageLimit(10);
        List<AgentMessage> messages = new ArrayList<>(messages(3, 2));
        messages.add(message("user", "tool_result", "记录".repeat(2000)));

        AgentCompactionService.Compacted compacted = compact(messages);

        assertThat(compacted.messages()).isEqualTo(messages);
        verifyNoInteractions(modelClient);
        verifyNoInteractions(eventProjector);
    }

    /**
     * 切点不能落在工具调用与它的结果之间。
     *
     * <p>落在两者之间，保留段就以一条 {@code tool_result} 打头，
     * 模型会读到一条不知道自己是怎么来的结果。</p>
     */
    @Test
    void neverCutsBetweenAToolCallAndItsResult() {
        properties.setContextMessageLimit(10);
        List<AgentMessage> plain = longMessages(12);
        assertThat(service.compactionCutIndex(plain)).isEqualTo(7);

        List<AgentMessage> withPair = new ArrayList<>(plain);
        withPair.set(6, message("assistant", "tool_call", "记录".repeat(200)));
        withPair.set(7, message("user", "tool_result", "记录".repeat(200)));

        // 自然切点 7 会把结果和它的调用切开，往前退一格把调用一起留在尾部。
        assertThat(service.compactionCutIndex(withPair)).isEqualTo(6);
    }

    /**
     * 摘要必须真的更小。
     *
     * <p>模型完全可能把一段对话"摘要"成更长的一篇复述；换上去就成了负优化。
     * 换不下来就不换——但那一次调用的 token 已经花掉了，用量照样要报账，
     * 否则压缩就成了一条绕过计费的模型调用。</p>
     */
    @Test
    void rejectsASummaryThatIsNotSmallerYetStillReportsTheTokensItBurned() {
        properties.setContextMessageLimit(10);
        stubSummary("摘".repeat(6000), new ModelUsage(3000, 0, 6000));
        List<AgentMessage> messages = longMessages(12);

        AgentCompactionService.Compacted compacted = compact(messages);

        assertThat(compacted.messages()).isEqualTo(messages);
        assertThat(compacted.usage()).isEqualTo(new ModelUsage(3000, 0, 6000));
        assertThat(compacted.model()).isEqualTo("agent-test-model");
        verifyNoCheckpointPersisted();
    }

    /** 压缩是省上下文的优化，它自己出问题不该把用户的这轮对话搭进去。 */
    @Test
    void fallsBackToTheOriginalWindowWhenSummarizationFails() {
        properties.setContextMessageLimit(10);
        when(modelClient.decideNextStepStream(anyList(), anyString(), any(), any()))
                .thenThrow(new IllegalStateException("上游 502"));
        List<AgentMessage> messages = longMessages(12);

        AgentCompactionService.Compacted compacted = compact(messages);

        assertThat(compacted.messages()).isEqualTo(messages);
        assertThat(compacted.usage()).isEqualTo(ModelUsage.NONE);
        verifyNoCheckpointPersisted();
    }

    /**
     * 模型没收口（返回了 action）同样算摘要失败，保持原样。
     *
     * <p>回归：这个分支早先在 fail-open 的 try 之外，模型一次不听话就会把用户这一轮
     * 变成失败的运行；而 token 已经花掉了，所以退回的同时必须把用量交回去。</p>
     */
    @Test
    void treatsADecisionThatDidNotCloseOutAsAFailedSummarizationInsteadOfATurnFailure() {
        properties.setContextMessageLimit(10);
        ObjectNode undecided = JsonNodeFactory.instance.objectNode();
        undecided.put("done", false);
        undecided.putObject("action").put("tool", "query_posts");
        when(modelClient.decideNextStepStream(anyList(), anyString(), any(), any()))
                .thenReturn(new ModelCallResult(undecided, new ModelUsage(900, 0, 30), "agent-test-model"));
        List<AgentMessage> messages = longMessages(12);

        AgentCompactionService.Compacted compacted = compact(messages);

        assertThat(compacted.messages()).isEqualTo(messages);
        assertThat(compacted.usage()).isEqualTo(new ModelUsage(900, 0, 30));
        verifyNoCheckpointPersisted();
    }

    /** 取消要穿过压缩：调用层据此走"已停止当前任务"的正常收尾，而不是当成一次失败的运行。 */
    @Test
    void propagatesCancellationInsteadOfSwallowingItAsAFailedCompaction() {
        properties.setContextMessageLimit(10);
        when(modelClient.decideNextStepStream(anyList(), anyString(), any(), any()))
                .thenThrow(new CancellationException("用户已停止"));

        assertThatThrownBy(() -> compact(longMessages(12)))
                .isInstanceOf(CancellationException.class);
        verifyNoCheckpointPersisted();
    }

    /** 代次已经被新一轮顶掉时，这一轮必须整体退场，不能靠 fail-open 继续跑下去。 */
    @Test
    void propagatesASupersededRun() {
        properties.setContextMessageLimit(10);
        when(modelClient.decideNextStepStream(anyList(), anyString(), any(), any()))
                .thenThrow(new AgentRunSupersededException(7L, 3L));

        assertThatThrownBy(() -> compact(longMessages(12)))
                .isInstanceOf(AgentRunSupersededException.class);
        verifyNoCheckpointPersisted();
    }

    /**
     * 摘要调用沿用主请求的工具面，指令追加在被压缩那一段之后。
     *
     * <p>于是这次调用是主请求的一个真前缀：instructions 逐字节相同、input 只在末尾多一轮，
     * 上游的前缀缓存能直接命中，真正新增的输入只有那条指令。</p>
     */
    @Test
    void summarizationReusesTheMainRequestToolSurfaceAndAppendsTheDirectiveLast() {
        properties.setContextMessageLimit(10);
        stubSummary("检查点：用户要整理笔记", ModelUsage.NONE);
        List<AgentMessage> messages = longMessages(12);
        messages.set(1, message("assistant", "answer", "第2条：" + "回复".repeat(200)));

        compact(messages);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AgentInputTurn>> turns = ArgumentCaptor.forClass(List.class);
        verify(modelClient).decideNextStepStream(turns.capture(), eq(TOOL_SURFACE), any(), any());
        List<AgentInputTurn> sent = turns.getValue();
        assertThat(sent.get(0).text()).contains("第1条");
        assertThat(sent).anyMatch(turn -> AgentInputTurn.ASSISTANT.equals(turn.role())
                && turn.text().contains("回复"));
        assertThat(sent.get(sent.size() - 1).role()).isEqualTo(AgentInputTurn.USER);
        assertThat(sent.get(sent.size() - 1).text())
                .contains(AgentCompactionService.COMPACTION_INSTRUCTION);
    }

    /** 地板是最近一个检查点，不是最早那个：更早的检查点已经被后一份合并进去了。 */
    @Test
    void theFloorIsTheNewestCheckpointNotTheOldestOne() {
        AgentMessage first = message("user", "message", "很早以前");
        AgentMessage older = message("user", AgentCompactionService.CHECKPOINT_KIND, "第一次压缩");
        AgentMessage middle = message("user", "message", "中间");
        AgentMessage newer = message("user", AgentCompactionService.CHECKPOINT_KIND, "第二次压缩");
        AgentMessage latest = message("user", "message", "最新");

        assertThat(AgentCompactionService.afterLatestCheckpoint(
                List.of(first, older, middle, newer, latest))).containsExactly(newer, latest);
        assertThat(AgentCompactionService.afterLatestCheckpoint(List.of(first, middle)))
                .containsExactly(first, middle);
        assertThat(AgentCompactionService.afterLatestCheckpoint(null)).isEmpty();
        assertThat(AgentCompactionService.afterLatestCheckpoint(List.of())).isEmpty();
    }

    /** 已经被检查点代表过的那一段不再参与压缩判断，否则同一段历史会被反复摘要。 */
    @Test
    void onlyLooksAtWhatIsAboveTheCurrentFloor() {
        properties.setContextMessageLimit(10);
        List<AgentMessage> history = longMessages(12);
        history.add(6, message("user", AgentCompactionService.CHECKPOINT_KIND, "上一次的检查点"));

        AgentCompactionService.Compacted compacted = compact(history);

        assertThat(compacted.messages()).isEqualTo(history.subList(6, history.size()));
        verifyNoInteractions(modelClient);
        verifyNoInteractions(eventProjector);
    }

    private AgentCompactionService.Compacted compact(List<AgentMessage> messages) {
        return service.compactIfNeeded(7L, 42L, 3L, messages, TOOL_SURFACE, () -> false);
    }

    private void stubSummary(String summary, ModelUsage usage) {
        ObjectNode decision = JsonNodeFactory.instance.objectNode();
        decision.put("done", true);
        decision.put("reply", summary);
        when(modelClient.decideNextStepStream(anyList(), anyString(), any(), any()))
                .thenReturn(new ModelCallResult(decision, usage, "agent-test-model"));
    }

    private void verifyNoCheckpointPersisted() {
        verify(eventProjector, never()).saveActiveMessage(any(), any(), org.mockito.ArgumentMatchers.anyLong(),
                any(), any(), any(), any());
    }

    /** 每条约 408 token：单条不足以撑到压力阈值，条数堆起来才会触发。 */
    private List<AgentMessage> longMessages(int count) {
        return messages(count, 200);
    }

    /** 正文刻意各不相同，避免"返回错了那一段"也能让相等断言通过。 */
    private List<AgentMessage> messages(int count, int repeat) {
        List<AgentMessage> list = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            list.add(message("user", "message", "第" + index + "条：" + "记录".repeat(repeat)));
        }
        return list;
    }

    private AgentMessage message(String role, String kind, String content) {
        AgentMessage message = new AgentMessage();
        message.setConversationId(7L);
        message.setRunVersion(3L);
        message.setRole(role);
        message.setKind(kind);
        message.setContent(content);
        return message;
    }
}
