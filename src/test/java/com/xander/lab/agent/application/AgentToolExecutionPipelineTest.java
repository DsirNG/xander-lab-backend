package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xander.lab.agent.domain.AgentRun;
import com.xander.lab.agent.domain.AgentStep;
import com.xander.lab.agent.domain.AgentToolInvocation;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies that pipeline validation happens before a tool invocation is durable or executable. */
class AgentToolExecutionPipelineTest {

    @Test
    void rejectsToolOutsideTheActiveSkillBeforeExecution() {
        AgentTool tool = tool("publish_post", "postId");
        AgentToolRegistry registry = new AgentToolRegistry(List.of(tool));
        AgentExecutionJournal journal = mock(AgentExecutionJournal.class);
        AgentToolExecutionPipeline pipeline = new AgentToolExecutionPipeline(registry, journal);

        assertThatThrownBy(() -> executeOne(pipeline, Set.of(), "publish_post", object("postId", 99)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不允许");

        verify(journal, never()).startInvocation(any(), any(), any(), any());
    }

    @Test
    void rejectsMissingRequiredArgumentBeforeExecution() {
        AgentTool tool = tool("publish_post", "postId");
        AgentToolRegistry registry = new AgentToolRegistry(List.of(tool));
        AgentExecutionJournal journal = mock(AgentExecutionJournal.class);
        AgentToolExecutionPipeline pipeline = new AgentToolExecutionPipeline(registry, journal);

        assertThatThrownBy(() -> executeOne(pipeline, Set.of("publish_post"), "publish_post",
                JsonNodeFactory.instance.objectNode()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("postId");

        verify(journal, never()).startInvocation(any(), any(), any(), any());
    }

    @Test
    void recordsInvocationBeforeCallingTheTool() {
        AgentTool tool = tool("query_posts");
        AgentToolRegistry registry = new AgentToolRegistry(List.of(tool));
        AgentExecutionJournal journal = mock(AgentExecutionJournal.class);
        AgentToolInvocation invocation = new AgentToolInvocation();
        invocation.setId(12L);
        when(journal.startInvocation(any(), any(), any(), any())).thenReturn(invocation);
        AgentToolExecutionPipeline pipeline = new AgentToolExecutionPipeline(registry, journal);

        executeOne(pipeline, Set.of("query_posts"), "query_posts", JsonNodeFactory.instance.objectNode());

        verify(journal).startInvocation(any(), any(), any(), any());
        verify(journal).completeInvocation(any(), any());
    }

    @Test
    void persistsApprovalAndDoesNotExecuteToolBeforeTheOwnerDecides() {
        AgentTool tool = new AgentTool() {
            @Override public String name() { return "publish_post"; }
            @Override public String description() { return "publish"; }
            @Override public JsonNode parametersSchema() { return JsonNodeFactory.instance.objectNode(); }
            @Override public java.util.Optional<String> approvalReason(JsonNode args) {
                return java.util.Optional.of("publishes content");
            }
            @Override public JsonNode execute(Long userId, JsonNode args, ProgressListener onProgress) {
                throw new AssertionError("approved tool must not execute before a decision");
            }
        };
        AgentToolRegistry registry = new AgentToolRegistry(List.of(tool));
        AgentExecutionJournal journal = mock(AgentExecutionJournal.class);
        AgentToolInvocation invocation = new AgentToolInvocation();
        invocation.setId(12L);
        invocation.setInvocationId("invocation-1");
        invocation.setToolName("publish_post");
        when(journal.startInvocation(any(), any(), any(), any())).thenReturn(invocation);
        AgentApprovalService approvals = mock(AgentApprovalService.class);
        com.xander.lab.agent.domain.AgentApproval approval = new com.xander.lab.agent.domain.AgentApproval();
        approval.setId(9L);
        when(approvals.request(any(), any(), any(), any(), any(), any())).thenReturn(approval);
        AgentToolExecutionPipeline pipeline = new AgentToolExecutionPipeline(registry, journal, approvals);

        assertThatThrownBy(() -> executeOne(pipeline, Set.of("publish_post"), "publish_post",
                JsonNodeFactory.instance.objectNode()))
                .isInstanceOf(AgentApprovalRequiredException.class);

        verify(journal).waitForApproval(invocation);
        verify(journal, never()).completeInvocation(any(), any());
    }

    @Test
    void leavesRemoteTimeoutOutcomeUnknownForReadOnlyReconciliation() {
        AgentTool tool = new AgentTool() {
            @Override public String name() { return "publish_post"; }
            @Override public String description() { return "publish"; }
            @Override public JsonNode parametersSchema() { return JsonNodeFactory.instance.objectNode(); }
            @Override public JsonNode execute(Long userId, JsonNode args, ProgressListener onProgress) {
                throw new IllegalStateException("publisher timed out", new SocketTimeoutException("read timed out"));
            }
        };
        AgentExecutionJournal journal = mock(AgentExecutionJournal.class);
        AgentToolInvocation invocation = new AgentToolInvocation();
        invocation.setId(12L);
        when(journal.startInvocation(any(), any(), any(), any())).thenReturn(invocation);
        AgentToolExecutionPipeline pipeline = new AgentToolExecutionPipeline(
                new AgentToolRegistry(List.of(tool)), journal);

        // 单调用也走批次入口，所以工具失败是**收敛成结果**而不是抛出去：
        // 编排循环拿到的是一条 failed 的结果，界面与日志里的行为与其它调用一致。
        List<ExecutedToolCall> results = executeOne(pipeline, Set.of("publish_post"), "publish_post",
                JsonNodeFactory.instance.objectNode());

        assertThat(results).hasSize(1);
        assertThat(results.get(0).failed()).isTrue();
        assertThat(results.get(0).failure()).isInstanceOf(IllegalStateException.class);

        verify(journal).markInvocationUnknown(org.mockito.ArgumentMatchers.eq(invocation),
                org.mockito.ArgumentMatchers.contains("未确认"));
        verify(journal, never()).failInvocation(any(), any());
    }

    /**
     * 单个调用 = 长度为 1 的批次。
     *
     * <p>执行边界只有一个入口（{@code executeBatch}），所以这里不再有"单调用专用方法"。
     * 早先那个版本已经没人调用，却仍有测试覆盖它——审批路由坏掉时正是它掩盖了问题。</p>
     */
    private List<ExecutedToolCall> executeOne(AgentToolExecutionPipeline pipeline, Set<String> allowedTools,
                                              String toolName, JsonNode args) {
        return pipeline.executeBatch(new AgentStep(), 42L, 7L, 3L, allowedTools,
                List.of(new AgentToolCall(toolName, args)), List.of(noop()),
                (call, invocationId) -> { }, false);
    }

    private AgentTool tool(String name, String... required) {
        return new AgentTool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public JsonNode parametersSchema() {
                var schema = JsonNodeFactory.instance.objectNode().put("type", "object");
                var fields = schema.putObject("properties");
                for (String field : required) fields.putObject(field).put("type", "integer");
                var requiredNode = schema.putArray("required");
                for (String field : required) requiredNode.add(field);
                return schema;
            }
            @Override public JsonNode execute(Long userId, JsonNode args, ProgressListener onProgress) {
                return JsonNodeFactory.instance.objectNode().put("ok", true);
            }
        };
    }

    private JsonNode object(String name, int value) {
        return JsonNodeFactory.instance.objectNode().put(name, value);
    }

    // ---- 批量执行 ----

    /**
     * 一批调用的日志顺序必须与模型给的顺序一致。并发只发生在工具体那一段，
     * 开单与收口都是串行按序的——否则消息历史会随线程调度乱序，模型读到的
     * "我问了 A、B，得到 X、Y"就变成了随机配对。
     */
    @Test
    void batchKeepsTheRequestOrderWhenOpeningAndSettlingInvocations() {
        AgentExecutionJournal journal = mock(AgentExecutionJournal.class);
        AgentToolExecutionPipeline pipeline =
                batchPipeline(List.of("query_knowledge", "query_posts"), null, journal);
        List<String> started = new ArrayList<>();

        List<ExecutedToolCall> results = pipeline.executeBatch(new AgentStep(), 42L, 7L, 3L,
                Set.of("query_knowledge", "query_posts"),
                List.of(call("query_knowledge"), call("query_posts")),
                List.of(noop(), noop()),
                (call, invocationId) -> started.add(call.toolName()), true);

        assertThat(started).containsExactly("query_knowledge", "query_posts");
        assertThat(results).extracting(result -> result.call().toolName())
                .containsExactly("query_knowledge", "query_posts");
        assertThat(results).noneMatch(ExecutedToolCall::failed);
    }

    /**
     * 管道必须在开单那一刻把身份交给监听器，而且交给它的必须与交给 {@code onStarted} 的是同一个。
     *
     * <p>不成立的话，前端收到的 tool_progress / tool_delta 会缺配对键，只能回退到按工具名配对——
     * 而同名工具恰好是回退会错的那一种（第二次的增量会画进第一张卡）。这里同时钉住
     * "每个调用都绑了"与"两个调用绑的不是同一个"。</p>
     */
    @Test
    void batchBindsEachInvocationIdToItsOwnProgressListener() {
        AgentExecutionJournal journal = mock(AgentExecutionJournal.class);
        AgentToolExecutionPipeline pipeline =
                batchPipeline(List.of("query_knowledge", "query_posts"), null, journal);
        RecordingProgress first = new RecordingProgress();
        RecordingProgress second = new RecordingProgress();
        Map<String, String> startedIds = new java.util.LinkedHashMap<>();

        pipeline.executeBatch(new AgentStep(), 42L, 7L, 3L,
                Set.of("query_knowledge", "query_posts"),
                List.of(call("query_knowledge"), call("query_posts")),
                List.of(first, second),
                (call, invocationId) -> startedIds.put(call.toolName(), invocationId), true);

        assertThat(first.boundInvocationId).isNotBlank()
                .isEqualTo(startedIds.get("query_knowledge"));
        assertThat(second.boundInvocationId).isNotBlank()
                .isEqualTo(startedIds.get("query_posts"));
        assertThat(first.boundInvocationId).isNotEqualTo(second.boundInvocationId);
    }

    @Test
    void oneFailingCallDoesNotAbortItsSiblings() {
        AgentExecutionJournal journal = mock(AgentExecutionJournal.class);
        AgentToolExecutionPipeline pipeline = batchPipeline(List.of("query_knowledge", "query_posts"),
                Map.of("query_knowledge", new IllegalStateException("boom")), journal);

        List<ExecutedToolCall> results = pipeline.executeBatch(new AgentStep(), 42L, 7L, 3L,
                Set.of("query_knowledge", "query_posts"),
                List.of(call("query_knowledge"), call("query_posts")),
                List.of(noop(), noop()),
                (call, invocationId) -> { }, false);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).failed()).isTrue();
        assertThat(results.get(0).failure()).hasMessage("boom");
        assertThat(results.get(1).failed()).isFalse();
        assertThat(results.get(1).result().path("ok").asBoolean()).isTrue();
    }

    /**
     * 审批是整轮级暂停，混在**多调用**批次里会在半途挂起，留下"半批已开单"的不可收拾状态。
     * 所以多调用批次必须在开单之前整批拒绝——断言的是 {@code never startInvocation}，
     * 不只是"抛了异常"。
     */
    @Test
    void batchRefusesApprovalRequiredCallsBeforeOpeningAnyInvocation() {
        AgentTool registryApproved = approvalRequiredTool("publish_post");
        AgentExecutionJournal journal = mock(AgentExecutionJournal.class);
        AgentToolExecutionPipeline pipeline = new AgentToolExecutionPipeline(
                new AgentToolRegistry(List.of(tool("query_knowledge"), registryApproved)), journal);

        assertThatThrownBy(() -> pipeline.executeBatch(new AgentStep(), 42L, 7L, 3L,
                Set.of("query_knowledge", "publish_post"),
                List.of(call("query_knowledge"), call("publish_post")),
                List.of(noop(), noop()), (call, invocationId) -> { }, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("审批");

        verify(journal, never()).startInvocation(any(), any(), any(), any());
    }

    /**
     * 回归：**单个**需要审批的调用必须走审批挂起，而不是被当成"批次里的审批"整批拒掉。
     *
     * <p>这条守着一个真实的自伤：批次化改造时把审批检查写成了无条件拒绝，而主循环已经不再调用
     * 单调用路径（{@code execute}），于是 {@link AgentApprovalRequiredException} 在生产代码里
     * 永远抛不出来——审批功能整个失效，用户看到的是"连续多次尝试后仍然失败"。
     * 所以这里既要断言抛的是审批异常，也要断言工具体**没有**被执行。</p>
     */
    @Test
    void singleApprovalRequiredCallOpensTheInvocationAndPausesForApproval() {
        AgentExecutionJournal journal = mock(AgentExecutionJournal.class);
        AgentApprovalService approvalService = mock(AgentApprovalService.class);
        AgentTool gated = approvalRequiredTool("publish_post");
        AgentToolExecutionPipeline pipeline = new AgentToolExecutionPipeline(
                new AgentToolRegistry(List.of(gated)), journal, approvalService);
        AgentToolInvocation invocation = new AgentToolInvocation();
        when(journal.startInvocation(any(), any(), any(), any())).thenReturn(invocation);
        when(approvalService.request(any(), any(), any(), any(), any(), any()))
                .thenReturn(new com.xander.lab.agent.domain.AgentApproval());

        assertThatThrownBy(() -> pipeline.executeBatch(new AgentStep(), 42L, 7L, 3L,
                Set.of("publish_post"), List.of(call("publish_post")), List.of(noop()),
                (call, invocationId) -> { }, false))
                .isInstanceOf(AgentApprovalRequiredException.class);

        // 开单与挂起都要发生：没有开单，审批批准后就没有可恢复的调用。
        verify(journal).startInvocation(any(), any(), any(), any());
        verify(journal).waitForApproval(invocation);
        verify(approvalService).request(any(), eq(invocation), eq(42L), eq(7L), any(), any());
    }

    /** 需要审批的桩工具：一旦真的被执行就炸，用来证明"挂起而不是执行"。 */
    private AgentTool approvalRequiredTool(String name) {
        return new AgentTool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public JsonNode parametersSchema() { return JsonNodeFactory.instance.objectNode(); }
            @Override public java.util.Optional<String> approvalReason(JsonNode args) {
                return java.util.Optional.of("publishes content");
            }
            @Override public JsonNode execute(Long userId, JsonNode args, ProgressListener onProgress) {
                throw new AssertionError("需要审批的工具不能在批准之前执行");
            }
        };
    }

    /** 第二个调用参数不合法时，第一个不能已经在日志里留下一条"已开始、永不收口"的记录。 */
    @Test
    void batchValidatesEveryCallBeforeOpeningAnyInvocation() {
        AgentExecutionJournal journal = mock(AgentExecutionJournal.class);
        AgentToolExecutionPipeline pipeline = new AgentToolExecutionPipeline(
                new AgentToolRegistry(List.of(tool("query_knowledge"), tool("publish_post", "postId"))), journal);

        assertThatThrownBy(() -> pipeline.executeBatch(new AgentStep(), 42L, 7L, 3L,
                Set.of("query_knowledge", "publish_post"),
                List.of(call("query_knowledge"), new AgentToolCall("publish_post",
                        JsonNodeFactory.instance.objectNode())),
                List.of(noop(), noop()), (call, invocationId) -> { }, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("postId");

        verify(journal, never()).startInvocation(any(), any(), any(), any());
    }

    /**
     * 并发批次要**真的同时跑**，不是排在同一线程上轮流跑。
     *
     * <p>用栅栏而不是记线程名：记线程名证明不了什么——池里两次 submit 挨得够近时，
     * 同一个线程就能把两个任务都跑完，看起来"只用了 1 个线程"。两个工具各自 await
     * 同一个 2 方栅栏，串行执行时第一个必然超时，那才是真的没并发。</p>
     */
    @Test
    void concurrentBatchRunsTheToolBodiesAtTheSameTime() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<AgentTool> tools = List.of(barrierTool("query_knowledge", barrier),
                barrierTool("query_posts", barrier));
        AgentExecutionJournal journal = mock(AgentExecutionJournal.class);
        when(journal.startInvocation(any(), any(), any(), any())).thenReturn(invocation());
        AgentToolExecutionPipeline pipeline = new AgentToolExecutionPipeline(
                new AgentToolRegistry(tools), journal);

        List<ExecutedToolCall> results = pipeline.executeBatch(new AgentStep(), 42L, 7L, 3L,
                Set.of("query_knowledge", "query_posts"),
                List.of(call("query_knowledge"), call("query_posts")),
                List.of(noop(), noop()), (call, invocationId) -> { }, true);

        assertThat(results).noneMatch(ExecutedToolCall::failed);
    }

    /**
     * 取消不能"立刻抛出"：并发批次里没轮到收口的 invocation 会永远停在 started 状态，
     * 之后 {@link AgentToolInvocationReconciler} 会把它们当成"结果未确认的副作用"逐个去远端查证。
     * 所以这里断言的是**每一条 invocation 都收到了结算**，而不是只断言异常抛了出来。
     */
    @Test
    void cancellationSettlesEveryInvocationBeforePropagating() {
        AgentExecutionJournal journal = mock(AgentExecutionJournal.class);
        AgentToolExecutionPipeline pipeline = batchPipeline(List.of("query_knowledge", "query_posts"),
                Map.of("query_knowledge", new java.util.concurrent.CancellationException("用户已停止")), journal);

        assertThatThrownBy(() -> pipeline.executeBatch(new AgentStep(), 42L, 7L, 3L,
                Set.of("query_knowledge", "query_posts"),
                List.of(call("query_knowledge"), call("query_posts")),
                List.of(noop(), noop()), (call, invocationId) -> { }, false))
                .isInstanceOf(java.util.concurrent.CancellationException.class);

        verify(journal, org.mockito.Mockito.times(2)).startInvocation(any(), any(), any(), any());
        verify(journal).cancelInvocation(any());
        verify(journal).completeInvocation(any(), any());
        verify(journal, never()).failInvocation(any(), any());
        verify(journal, never()).markInvocationUnknown(any(), any());
    }

    // ---- 副作用身份（Effect）接线 ----

    /**
     * 只读工具不建 Effect，幂等键退回 {@code invocationId}。
     *
     * <p>{@code readOnly()} 的第二个、也是更重要的用途就在这里：它原本只表示"并发安全"，
     * 现在同时回答"这次调用要不要有业务幂等身份"。两者是同一个事实的两种说法——
     * 只读就没有副作用，也就没有需要幂等的动作。若不按它分流，每次 {@code query_posts}
     * 都会铸一个 Effect，而那个键永远没有下游在等它。</p>
     */
    @Test
    void aReadOnlyToolGetsNoEffectAndKeysOnItsInvocationId() {
        AtomicReference<AgentToolContext> seen = new AtomicReference<>();
        AgentTool tool = recordingTool("query_posts", true, seen);
        AgentExecutionJournal journal = mock(AgentExecutionJournal.class);
        when(journal.startInvocation(any(), any(), any(), any())).thenReturn(invocation());
        AgentEffectService effects = mock(AgentEffectService.class);
        AgentToolExecutionPipeline pipeline = new AgentToolExecutionPipeline(
                new AgentToolRegistry(List.of(tool)), journal, null, effects);

        executeOne(pipeline, Set.of("query_posts"), "query_posts", JsonNodeFactory.instance.objectNode());

        verify(effects, never()).resolveEffect(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                any(), any());
        verify(journal, never()).attachEffect(any(), any());
        assertThat(seen.get().hasEffect()).isFalse();
        assertThat(seen.get().idempotencyKey()).isEqualTo(seen.get().invocationId());
    }

    /**
     * 写工具拿到 Effect，并且**下游幂等键取的是 effectId 而不是 invocationId**。
     *
     * <p>这正是 B6 要修的那件事：{@code invocationId} 每次调用都是新 UUID，模型看到 {@code cancelled}
     * 后重新发起同一个调用会带一个新键，下游 {@code UNIQUE (user_id, client_request_id)} 不命中，
     * 于是真会执行第二次。{@code effectId} 跨多次调用不变，重试因此被下游认成同一件事。</p>
     */
    @Test
    void aWriteToolKeysItsDownstreamCallOnTheEffectNotTheInvocation() {
        AtomicReference<AgentToolContext> seen = new AtomicReference<>();
        AgentTool tool = recordingTool("publish_post", false, seen);
        AgentExecutionJournal journal = mock(AgentExecutionJournal.class);
        AgentToolInvocation invocation = invocation();
        when(journal.startInvocation(any(), any(), any(), any())).thenReturn(invocation);
        stubAttachEffectSyncingMemory(journal);
        AgentEffectService effects = mock(AgentEffectService.class);
        AgentRun run = runWithEpoch();
        when(journal.findRun(any())).thenReturn(java.util.Optional.of(run));
        when(effects.resolveEffect(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
                .thenReturn("effect-1");
        AgentToolExecutionPipeline pipeline = new AgentToolExecutionPipeline(
                new AgentToolRegistry(List.of(tool)), journal, null, effects);

        executeOne(pipeline, Set.of("publish_post"), "publish_post", JsonNodeFactory.instance.objectNode());

        verify(journal).attachEffect(invocation, "effect-1");
        assertThat(seen.get().hasEffect()).isTrue();
        assertThat(seen.get().effectId()).isEqualTo("effect-1");
        assertThat(seen.get().idempotencyKey()).isEqualTo("effect-1");
        // 两个身份没有被合并：effect 是下游幂等键，invocation 仍是日志里的配对键。
        assertThat(seen.get().invocationId()).isNotEqualTo("effect-1");
    }

    /**
     * Effect 必须在工具开跑**之前**就已经是持久事实。
     *
     * <p>反过来的话，崩溃窗口里没人能回答"它到底写了没有"——下游可能已经按这个键写了，
     * 而本地还没有任何记录提到过这个键。所以这里断的是时序，不是"有没有调用"。</p>
     */
    @Test
    void theEffectIsAttachedBeforeTheToolRuns() {
        AtomicReference<Boolean> attachedWhenExecuting = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean attached = new java.util.concurrent.atomic.AtomicBoolean();
        AgentTool tool = new AgentTool() {
            @Override public String name() { return "publish_post"; }
            @Override public String description() { return "publish"; }
            @Override public boolean readOnly() { return false; }
            @Override public JsonNode parametersSchema() { return JsonNodeFactory.instance.objectNode(); }
            @Override public JsonNode execute(Long userId, JsonNode args, ProgressListener onProgress) {
                attachedWhenExecuting.set(attached.get());
                return JsonNodeFactory.instance.objectNode().put("ok", true);
            }
        };
        AgentExecutionJournal journal = mock(AgentExecutionJournal.class);
        when(journal.startInvocation(any(), any(), any(), any())).thenReturn(invocation());
        org.mockito.Mockito.doAnswer(invocationOnMock -> {
            attached.set(true);
            return null;
        }).when(journal).attachEffect(any(), any());
        AgentEffectService effects = mock(AgentEffectService.class);
        when(journal.findRun(any())).thenReturn(java.util.Optional.of(runWithEpoch()));
        when(effects.resolveEffect(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
                .thenReturn("effect-1");
        AgentToolExecutionPipeline pipeline = new AgentToolExecutionPipeline(
                new AgentToolRegistry(List.of(tool)), journal, null, effects);

        executeOne(pipeline, Set.of("publish_post"), "publish_post", JsonNodeFactory.instance.objectNode());

        assertThat(attachedWhenExecuting.get()).isTrue();
    }

    /**
     * 副作用边界标记必须写在把请求交给工具**之前**。
     *
     * <p>顺序不能反：崩在"标记"与"发送"之间只会留下一个假 {@code DISPATCHED}（多一次无谓对账，
     * 安全方向）；反过来则会漏掉一次真实写入——那条写入会被崩溃收口当成"没越过边界"而静默终结，
     * 用户重发就是第二次写（要命方向）。所以这里断的是时序，不是"有没有调用"。</p>
     *
     * <p>这是两个执行点之一；审批恢复那条路绕开了本管道，由
     * {@code AgentConversationServiceTest} 单独守。</p>
     */
    @Test
    void theDispatchMarkIsWrittenBeforeTheToolRuns() {
        java.util.concurrent.atomic.AtomicBoolean dispatched = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean dispatchedWhenExecuting =
                new java.util.concurrent.atomic.AtomicBoolean();
        AgentTool tool = new AgentTool() {
            @Override public String name() { return "publish_post"; }
            @Override public String description() { return "publish"; }
            @Override public boolean readOnly() { return false; }
            @Override public JsonNode parametersSchema() { return JsonNodeFactory.instance.objectNode(); }
            @Override public JsonNode execute(Long userId, JsonNode args, ProgressListener onProgress) {
                dispatchedWhenExecuting.set(dispatched.get());
                return JsonNodeFactory.instance.objectNode().put("ok", true);
            }
        };
        AgentExecutionJournal journal = mock(AgentExecutionJournal.class);
        AgentToolInvocation invocation = invocation();
        when(journal.startInvocation(any(), any(), any(), any())).thenReturn(invocation);
        org.mockito.Mockito.doAnswer(ignored -> {
            dispatched.set(true);
            return null;
        }).when(journal).markEffectDispatched(any());
        AgentEffectService effects = mock(AgentEffectService.class);
        when(journal.findRun(any())).thenReturn(java.util.Optional.of(runWithEpoch()));
        when(effects.resolveEffect(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
                .thenReturn("effect-1");
        AgentToolExecutionPipeline pipeline = new AgentToolExecutionPipeline(
                new AgentToolRegistry(List.of(tool)), journal, null, effects);

        executeOne(pipeline, Set.of("publish_post"), "publish_post", JsonNodeFactory.instance.objectNode());

        assertThat(dispatchedWhenExecuting.get()).isTrue();
        verify(journal).markEffectDispatched(invocation);
    }

    /**
     * 迁移前的 Run 没有周期标识，写工具退回 {@code invocationId}。
     *
     * <p>退回是刻意的：没有周期就界不定"同一件事"的范围，凭猜测编一个会把两件不同的事合并。
     * 退回虽然恢复了"重试带新键"的老问题，但那是**已知**的、而且只影响迁移前就存在的那些 Run；
     * 猜错周期则是把用户的两个动作合成一个，更难发现。</p>
     */
    @Test
    void aWriteToolInARunWithoutAnEpochFallsBackToItsInvocationId() {
        AtomicReference<AgentToolContext> seen = new AtomicReference<>();
        AgentTool tool = recordingTool("publish_post", false, seen);
        AgentExecutionJournal journal = mock(AgentExecutionJournal.class);
        when(journal.startInvocation(any(), any(), any(), any())).thenReturn(invocation());
        AgentEffectService effects = mock(AgentEffectService.class);
        AgentRun run = new AgentRun();
        run.setId(17L);
        run.setIntentEpoch(null);
        when(journal.findRun(any())).thenReturn(java.util.Optional.of(run));
        when(effects.resolveEffect(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
                .thenReturn(null);
        AgentToolExecutionPipeline pipeline = new AgentToolExecutionPipeline(
                new AgentToolRegistry(List.of(tool)), journal, null, effects);

        executeOne(pipeline, Set.of("publish_post"), "publish_post", JsonNodeFactory.instance.objectNode());

        verify(journal, never()).attachEffect(any(), any());
        assertThat(seen.get().hasEffect()).isFalse();
        assertThat(seen.get().idempotencyKey()).isEqualTo(seen.get().invocationId());
    }

    /**
     * 一批只读调用**一次都不读 Run**。
     *
     * <p>纯只读批次不需要周期标识，为一件它用不上的事多一次数据库往返是白花的。这条同时是
     * "第 1.5 段为什么放在整批而不是每个调用"的守卫。</p>
     */
    @Test
    void aReadOnlyBatchNeverReadsTheRun() {
        AgentExecutionJournal journal = mock(AgentExecutionJournal.class);
        AgentToolExecutionPipeline pipeline =
                batchPipeline(List.of("query_knowledge", "query_posts"), null, journal);

        pipeline.executeBatch(new AgentStep(), 42L, 7L, 3L,
                Set.of("query_knowledge", "query_posts"),
                List.of(call("query_knowledge"), call("query_posts")),
                List.of(noop(), noop()), (call, invocationId) -> { }, false);

        verify(journal, never()).findRun(any());
    }

    /**
     * 一批写调用只读**一次** Run，而不是每个调用读一次。
     *
     * <p>身份解析要读 {@code intent_epoch}，而同一批里的所有调用属于同一个 Run——每个调用各读一次
     * 是同一行数据读 N 遍。这个用例钉住那个优化没有被后来的改动悄悄退回去。</p>
     */
    @Test
    void aWriteBatchReadsTheRunOnlyOnce() {
        AtomicReference<AgentToolContext> first = new AtomicReference<>();
        AtomicReference<AgentToolContext> second = new AtomicReference<>();
        AgentExecutionJournal journal = mock(AgentExecutionJournal.class);
        when(journal.startInvocation(any(), any(), any(), any())).thenReturn(invocation());
        AgentEffectService effects = mock(AgentEffectService.class);
        when(journal.findRun(any())).thenReturn(java.util.Optional.of(runWithEpoch()));
        when(effects.resolveEffect(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
                .thenReturn("effect-1");
        AgentToolExecutionPipeline pipeline = new AgentToolExecutionPipeline(
                new AgentToolRegistry(List.of(recordingTool("publish_post", false, first),
                        recordingTool("email_reminder", false, second))), journal, null, effects);
        AgentStep step = new AgentStep();
        step.setRunId(17L);

        pipeline.executeBatch(step, 42L, 7L, 3L, Set.of("publish_post", "email_reminder"),
                List.of(call("publish_post"), call("email_reminder")),
                List.of(noop(), noop()), (call, invocationId) -> { }, false);

        verify(journal, org.mockito.Mockito.times(1)).findRun(any());
    }

    /**
     * 日志配对键仍然是 {@code invocationId}，不是 {@code effectId}。
     *
     * <p>这两层身份各有各的用途，**合并它们是本批最容易犯的错**：前端按 {@code invocationId} 把
     * tool_progress / tool_result 配到对应的工具卡上，而 {@code effectId} 是给下游去重用的。
     * 用 effectId 当配对键的话，同一个 Effect 的两次调用会在界面上画成同一张卡——第二次的输出
     * 覆盖第一次，用户看不到"重试过"这件事。</p>
     */
    @Test
    void theExecutedCallStillReportsItsInvocationIdAsTheLogPairingKey() {
        AgentExecutionJournal journal = mock(AgentExecutionJournal.class);
        // 桩要忠实：真实的 startInvocation 会把**传进来的** invocationId 落到实体上，
        // 而管道现在从实体读回它来当日志配对键。所以这里用传入值造返回值，
        // 而不是返回一个 invocationId 为空的壳——后者会让断言读到 null，测的却是桩的失真。
        List<String> startedIds = new ArrayList<>();
        org.mockito.Mockito.doAnswer(invocationOnMock -> {
            String startedId = invocationOnMock.getArgument(1);
            startedIds.add(startedId);
            return invocation(startedId);
        }).when(journal).startInvocation(any(), any(), any(), any());
        AgentEffectService effects = mock(AgentEffectService.class);
        when(journal.findRun(any())).thenReturn(java.util.Optional.of(runWithEpoch()));
        when(effects.resolveEffect(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
                .thenReturn("effect-1");
        AgentToolExecutionPipeline pipeline = new AgentToolExecutionPipeline(
                new AgentToolRegistry(List.of(recordingTool("publish_post", false,
                        new AtomicReference<>()))), journal, null, effects);

        List<ExecutedToolCall> results = executeOne(pipeline, Set.of("publish_post"), "publish_post",
                JsonNodeFactory.instance.objectNode());

        assertThat(startedIds).hasSize(1);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).invocationId()).isEqualTo(startedIds.get(0));
        assertThat(results.get(0).invocationId()).isNotEqualTo("effect-1");
    }

    /** 记录它收到的调用上下文，用于断言"工具看到的幂等键是哪一个"。 */
    private AgentTool recordingTool(String name, boolean readOnly, AtomicReference<AgentToolContext> seen) {
        return new AgentTool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public boolean readOnly() { return readOnly; }
            @Override public JsonNode parametersSchema() { return JsonNodeFactory.instance.objectNode(); }
            @Override public JsonNode execute(Long userId, JsonNode args, ProgressListener onProgress) {
                return JsonNodeFactory.instance.objectNode().put("ok", true);
            }
            @Override public JsonNode execute(AgentToolContext context, JsonNode args,
                                               ProgressListener onProgress) {
                seen.set(context);
                return JsonNodeFactory.instance.objectNode().put("ok", true);
            }
        };
    }

    private AgentRun runWithEpoch() {
        AgentRun run = new AgentRun();
        run.setId(17L);
        run.setConversationId(7L);
        run.setUserId(42L);
        run.setIntentEpoch("6f1c0a44-1f0e-4a3c-9a2f-6b7c1d2e3f40");
        return run;
    }

    private AgentToolExecutionPipeline batchPipeline(List<String> toolNames, Map<String, RuntimeException> failures,
                                                    AgentExecutionJournal journal) {
        List<AgentTool> tools = new ArrayList<>();
        for (String name : toolNames) {
            RuntimeException failure = failures == null ? null : failures.get(name);
            tools.add(new AgentTool() {
                @Override public String name() { return name; }
                @Override public String description() { return name; }
                @Override public boolean readOnly() { return true; }
                @Override public JsonNode parametersSchema() { return JsonNodeFactory.instance.objectNode(); }
                @Override public JsonNode execute(Long userId, JsonNode args, ProgressListener onProgress) {
                    if (failure != null) throw failure;
                    return JsonNodeFactory.instance.objectNode().put("ok", true).put("tool", name);
                }
            });
        }
        when(journal.startInvocation(any(), any(), any(), any())).thenReturn(invocation());
        return new AgentToolExecutionPipeline(new AgentToolRegistry(tools), journal);
    }

    private AgentTool barrierTool(String name, CyclicBarrier barrier) {
        return new AgentTool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public boolean readOnly() { return true; }
            @Override public JsonNode parametersSchema() { return JsonNodeFactory.instance.objectNode(); }
            @Override public JsonNode execute(Long userId, JsonNode args, ProgressListener onProgress) {
                try {
                    barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception exception) {
                    // 串行执行时第一个到这里就超时，任务失败，测试断言"没有失败"随即变红。
                    throw new IllegalStateException("两个调用没有同时进入执行", exception);
                }
                return JsonNodeFactory.instance.objectNode().put("ok", true);
            }
        };
    }

    private AgentToolInvocation invocation() {
        return invocation("invocation-12");
    }

    /**
     * 一个**忠实于真实 {@code startInvocation} 返回值**的桩。
     *
     * <p>{@code invocationId} 必须写上：管道现在从**实体**上读它来构造工具上下文与日志配对键，
     * 而不是自己另存一份（原来那个 {@code CallIdentity} 记录就是那份副本）。真实实现里
     * {@code startInvocation} 会把传入的 id 落到实体上，所以桩不写它就会让工具看到 {@code null}
     * ——那是桩不真实，不是实现错。</p>
     */
    private AgentToolInvocation invocation(String invocationId) {
        AgentToolInvocation invocation = new AgentToolInvocation();
        invocation.setId(12L);
        invocation.setInvocationId(invocationId);
        invocation.setStatus(AgentToolInvocation.STATUS_RUNNING);
        return invocation;
    }

    /**
     * 让 journal 的桩忠实于真实实现：{@code attachEffect} 除了落库，还会把 {@code effectId}
     * **写回内存对象**（真实实现里有专门的测试钉住这条契约）。管道随后从实体上读回它交给工具，
     * 所以桩不写回的话，工具会看到 {@code null}。
     */
    private static void stubAttachEffectSyncingMemory(AgentExecutionJournal journal) {
        org.mockito.Mockito.doAnswer(call -> {
            call.getArgument(0, AgentToolInvocation.class).setEffectId(call.getArgument(1));
            return null;
        }).when(journal).attachEffect(any(), any());
    }

    private AgentToolCall call(String toolName) {
        return new AgentToolCall(toolName, JsonNodeFactory.instance.objectNode());
    }

    private AgentTool.ProgressListener noop() {
        return message -> { };
    }

    /** 记下被绑定的调用身份；这个用例不关心进度内容，只关心配对键有没有落到对的监听器上。 */
    private static final class RecordingProgress implements AgentTool.ProgressListener {
        private String boundInvocationId;

        @Override
        public void onProgress(String message) {
        }

        @Override
        public void bindInvocationId(String invocationId) {
            this.boundInvocationId = invocationId;
        }
    }
}
