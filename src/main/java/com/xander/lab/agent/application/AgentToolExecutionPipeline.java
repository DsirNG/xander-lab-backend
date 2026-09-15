package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.xander.lab.agent.domain.AgentStep;
import com.xander.lab.agent.domain.AgentToolInvocation;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;

/**
 * The only execution boundary for model-requested tools in a conversation run.
 *
 * <p>Visibility and argument checks happen before an invocation is persisted. The stable invocation
 * identity is then durable before the tool starts. Approval, idempotency reconciliation and timeout
 * policies extend this pipeline rather than being implemented by individual skills.</p>
 */
@Service
public class AgentToolExecutionPipeline {
    /**
     * 并发批次的执行池。
     *
     * <p>刻意与请求线程隔离，也不用公共 ForkJoinPool：工具体里是阻塞的数据库读，
     * 压在公共池上会把它拖住，连带影响同一 JVM 里所有依赖公共池的代码。
     * 池是全局共享的（不是每批次新建），因为批次很短、创建线程的代价比排队还高；
     * 单步最多 {@code agent.conversation.max-parallel-tools} 个调用，排队长度天然有限。</p>
     */
    private static final int BATCH_POOL_SIZE = 8;

    private final AgentToolRegistry toolRegistry;
    private final AgentExecutionJournal executionJournal;
    private final AgentApprovalService approvalService;
    private volatile ExecutorService batchExecutor;

    public AgentToolExecutionPipeline(AgentToolRegistry toolRegistry, AgentExecutionJournal executionJournal) {
        this(toolRegistry, executionJournal, null);
    }

    @Autowired
    public AgentToolExecutionPipeline(AgentToolRegistry toolRegistry, AgentExecutionJournal executionJournal,
                                      AgentApprovalService approvalService) {
        this.toolRegistry = toolRegistry;
        this.executionJournal = executionJournal;
        this.approvalService = approvalService;
    }

    /** Compatibility factory for focused legacy loop tests without journal persistence. */
    static AgentToolExecutionPipeline legacy(AgentToolRegistry toolRegistry) {
        return new AgentToolExecutionPipeline(toolRegistry, AgentExecutionJournal.disabled());
    }

    /**
     * 一步里执行多个工具调用，日志与消息按**模型给的顺序**落库。
     *
     * <p>分四段，是为了让"并发"只发生在真正安全的那一段：</p>
     * <ol>
     *   <li><b>校验</b>（串行）——整批先全部校验完再开单。边校验边开单的话，第二个调用参数不合法时
     *       第一个已经在日志里留下一条"已开始、永不收口"的记录。</li>
     *   <li><b>开单</b>（串行、按序）——{@code startInvocation} 与 {@code tool_start} 按模型给的顺序写入。</li>
     *   <li><b>执行</b>——{@code concurrent} 为真时放线程池并发，否则按序串行。</li>
     *   <li><b>收口</b>（串行、按序）——逐条完成/失败结算。</li>
     * </ol>
     *
     * <p>取消**不立刻抛出**：先把所有调用结算完再抛。并发批次里若有调用还没轮到结算，
     * 直接抛会把它们永远留在 started 状态，而 {@link AgentToolInvocationReconciler} 之后
     * 会把它们当成"结果未确认的副作用"逐个去远端查证——用户主动按一下停止，换来一串无谓的查询。</p>
     *
     * <p>需要审批的调用：<b>单个调用</b>开单后请求审批并抛出
     * {@link AgentApprovalRequiredException}（调用方接住它去挂起整轮）；<b>多调用批次</b>里出现
     * 需要审批的调用则整批拒绝，因为"批准后只执行那一半"无法表达。详见第 1、2 段的注释。</p>
     *
     * <p>进度事件的推送被串行化（见 {@link SerializedProgress}）：工具体并发，但
     * 推送与落库仍是串行的一段，免得两条 `tool_progress` 交错写进同一个 SSE 出口。</p>
     *
     * @param progressListeners 与 {@code calls} 按下标一一对应；开单时管道会把这次调用的
     *                          {@code invocationId} 交给对应监听器（见
     *                          {@link AgentTool.ProgressListener#bindInvocationId}），
     *                          因此并发批次里两个同名工具各自的事件不会串
     * @param onStarted         收到 {@code (call, invocationId)}，用于落 {@code tool_call} 消息与事件
     * @return 与 {@code calls} 同序的结果；单个调用失败在它自己的槽位里，不影响同批其它调用
     * @throws AgentApprovalRequiredException 单个调用需要审批；此时该调用已开单并挂起，
     *                                        **没有**返回值，调用方必须挂起整轮而不是当作失败
     */
    public List<ExecutedToolCall> executeBatch(AgentStep step, Long userId, Long conversationId, long runVersion,
                                               Set<String> allowedTools, List<AgentToolCall> calls,
                                               List<AgentTool.ProgressListener> progressListeners,
                                               BiConsumer<AgentToolCall, String> onStarted, boolean concurrent) {
        if (calls.isEmpty()) return List.of();
        if (progressListeners.size() != calls.size()) {
            throw new IllegalArgumentException("并发批次里每个调用必须配一个进度监听器");
        }
        Object emissionLock = new Object();

        // 第 1 段：整批校验。任何一条不合法都在开单之前抛出，不留半批记录。
        //
        // 审批单独一条规则：**多调用批次里出现需要审批的调用，整批拒绝**。原因是审批是整轮级
        // 暂停，"批准后只执行那一半"无法表达——整批重来会让前一半重复执行（写操作重复就是事故），
        // 半批待批又会在日志里留下永不收口的开单。让模型把那一个单独发起即可。
        // 单个调用不在拒绝之列：它在第 2 段开单后直接请求审批并抛出（见那里的注释）。
        boolean multiCall = calls.size() > 1;
        List<AgentTool> tools = new ArrayList<>(calls.size());
        for (AgentToolCall call : calls) {
            String toolName = call.toolName();
            if (toolName == null || !allowedTools.contains(toolName)) {
                throw new IllegalArgumentException("当前 Skill 不允许调用工具 " + safeToolName(toolName));
            }
            AgentTool tool = toolRegistry.get(toolName);
            if (tool == null) throw new IllegalArgumentException("未知工具 " + safeToolName(toolName));
            validateArguments(tool, call.args());
            if (multiCall && tool.approvalReason(call.args()).isPresent()) {
                throw new IllegalStateException("需要审批的工具必须单独调用，不能和别的调用放在同一批");
            }
            tools.add(tool);
        }

        // 第 2 段：按序开单。
        List<AgentToolInvocation> invocations = new ArrayList<>(calls.size());
        List<String> invocationIds = new ArrayList<>(calls.size());
        for (int index = 0; index < calls.size(); index++) {
            AgentToolCall call = calls.get(index);
            progressListeners.get(index).checkCancellation();
            String invocationId = UUID.randomUUID().toString();
            // 身份必须在这里就交给监听器，而不是等到执行：工具一开跑就可能推进度，
            // 那时监听器若还不知道自己属于哪次调用，推出去的事件就少了配对键。
            progressListeners.get(index).bindInvocationId(invocationId);
            AgentToolInvocation invocation =
                    executionJournal.startInvocation(step, invocationId, call.toolName(), call.args());
            onStarted.accept(call, invocationId);

            // 单个调用的审批就在开单之后停下，主循环接住这个异常去挂起整轮。
            // 这里挂起不会留下"半批已开单"：多调用批次含审批的情形在第 1 段已经整批拒绝，
            // 所以走到这一步的批次必然只有这一个调用。
            var approvalReason = tools.get(index).approvalReason(call.args());
            if (approvalReason.isPresent()) {
                if (approvalService == null) {
                    throw new IllegalStateException("当前执行环境未配置工具审批服务");
                }
                var approval = approvalService.request(step, invocation, userId, conversationId,
                        approvalReason.get(), call.args());
                executionJournal.waitForApproval(invocation);
                throw new AgentApprovalRequiredException(approval);
            }

            invocations.add(invocation);
            invocationIds.add(invocationId);
        }

        // 第 3 段：执行。并发只在调用方判定"整批只读且无审批"时才会传 true。
        List<Outcome> outcomes = concurrent
                ? runConcurrently(calls, tools, userId, conversationId, runVersion, invocationIds,
                        progressListeners, emissionLock)
                : runSequentially(calls, tools, userId, conversationId, runVersion, invocationIds,
                        progressListeners, emissionLock);

        // 第 4 段：按序收口。取消留到最后再抛，保证每条 invocation 都已结算。
        CancellationException cancellation = null;
        List<ExecutedToolCall> results = new ArrayList<>(calls.size());
        for (int index = 0; index < calls.size(); index++) {
            AgentToolCall call = calls.get(index);
            AgentToolInvocation invocation = invocations.get(index);
            String invocationId = invocationIds.get(index);
            Outcome outcome = outcomes.get(index);
            if (outcome.failure() instanceof CancellationException cancelled) {
                executionJournal.cancelInvocation(invocation);
                if (cancellation == null) cancellation = cancelled;
                results.add(new ExecutedToolCall(call, invocationId, null, cancelled));
                continue;
            }
            if (outcome.failure() == null) {
                executionJournal.completeInvocation(invocation, outcome.result());
                results.add(new ExecutedToolCall(call, invocationId, outcome.result(), null));
                continue;
            }
            Exception failure = outcome.failure();
            if (AgentToolOutcomeClassifier.requiresReconciliation(failure)) {
                executionJournal.markInvocationUnknown(invocation,
                        "工具远端执行结果未确认：" + safeMessage(failure));
            } else {
                executionJournal.failInvocation(invocation, safeMessage(failure));
            }
            results.add(new ExecutedToolCall(call, invocationId, null, failure));
        }
        if (cancellation != null) throw cancellation;
        return results;
    }

    private List<Outcome> runSequentially(List<AgentToolCall> calls, List<AgentTool> tools, Long userId,
                                          Long conversationId, long runVersion,
                                          List<String> invocationIds,
                                          List<AgentTool.ProgressListener> progressListeners, Object lock) {
        List<Outcome> outcomes = new ArrayList<>(calls.size());
        for (int index = 0; index < calls.size(); index++) {
            outcomes.add(invoke(calls.get(index), tools.get(index), userId, conversationId, runVersion,
                    invocationIds.get(index),
                    new SerializedProgress(progressListeners.get(index), lock)));
        }
        return outcomes;
    }

    private List<Outcome> runConcurrently(List<AgentToolCall> calls, List<AgentTool> tools, Long userId,
                                          Long conversationId, long runVersion,
                                          List<String> invocationIds,
                                          List<AgentTool.ProgressListener> progressListeners, Object lock) {
        List<Future<Outcome>> futures = new ArrayList<>(calls.size());
        for (int index = 0; index < calls.size(); index++) {
            final int slot = index;
            futures.add(batchExecutor().submit(() -> invoke(calls.get(slot), tools.get(slot), userId, conversationId,
                    runVersion, invocationIds.get(slot),
                    new SerializedProgress(progressListeners.get(slot), lock))));
        }
        List<Outcome> outcomes = new ArrayList<>(calls.size());
        for (Future<Outcome> future : futures) {
            try {
                outcomes.add(future.get());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                outcomes.add(Outcome.cancelled());
            } catch (ExecutionException exception) {
                // invoke() 已经吞掉了所有异常，走到这里只可能是池本身的问题（例如关闭中）。
                outcomes.add(new Outcome(null, new IllegalStateException("工具执行线程异常："
                        + safeMessage(exception), exception)));
            }
        }
        return outcomes;
    }

    /** 跑一个工具体，把异常收敛成 {@link Outcome}，绝不向调用方抛出。 */
    private Outcome invoke(AgentToolCall call, AgentTool tool, Long userId, Long conversationId, long runVersion,
                           String invocationId, AgentTool.ProgressListener progress) {
        try {
            JsonNode result = tool.execute(
                    new AgentToolContext(userId, conversationId, runVersion, invocationId), call.args(), progress);
            return new Outcome(result, null);
        } catch (CancellationException exception) {
            return Outcome.cancelled();
        } catch (Exception exception) {
            return new Outcome(null, exception);
        }
    }

    /**
     * 并发批次的执行池，**用到才建**。
     *
     * <p>延迟创建不是洁癖：{@code newFixedThreadPool} 的核心线程没有空闲回收，建了就一直留着。
     * 这个类在测试里会被反复 new 出来，字段初始化式建池等于每跑一个用例就漏 8 个线程；
     * 而线上只要没人真的并发跑过工具，也不该先占着线程。</p>
     */
    private ExecutorService batchExecutor() {
        ExecutorService executor = batchExecutor;
        if (executor != null) return executor;
        synchronized (this) {
            if (batchExecutor == null) {
                batchExecutor = Executors.newFixedThreadPool(BATCH_POOL_SIZE, runnable -> {
                    Thread thread = new Thread(runnable, "agent-tool-batch");
                    thread.setDaemon(true);
                    return thread;
                });
            }
            return batchExecutor;
        }
    }

    @PreDestroy
    void shutdownBatchExecutor() {
        synchronized (this) {
            if (batchExecutor != null) batchExecutor.shutdownNow();
        }
    }

    private void validateArguments(AgentTool tool, JsonNode args) {
        if (args == null || !args.isObject()) {
            throw new IllegalArgumentException("工具参数必须是 JSON 对象");
        }
        JsonNode required = tool.parametersSchema().path("required");
        for (JsonNode field : required) {
            String name = field.asText("");
            if (!name.isBlank() && (!args.has(name) || args.path(name).isNull())) {
                throw new IllegalArgumentException("工具参数缺少 " + name);
            }
        }
    }

    private String safeToolName(String toolName) {
        return toolName == null || toolName.isBlank() ? "（空工具名）" : toolName;
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return "工具执行失败";
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }

    /** 一次工具体执行的结果：成功带结果，失败带异常，两者互斥。 */
    private record Outcome(JsonNode result, Exception failure) {
        static Outcome cancelled() {
            return new Outcome(null, new CancellationException("用户已停止当前智能体任务"));
        }
    }

    /**
     * 把并发批次里的事件推送串行化。
     *
     * <p>工具体并发跑，但它们都往同一个会话的 SSE 出口推 `tool_progress` / `tool_delta`。
     * 两个线程同时写同一条流，轻则前端收到交错的半条 JSON，重则把连接写坏。
     * 这里共用一把批次锁：推送变成串行的一段，工具本身该并发还是并发。</p>
     */
    private record SerializedProgress(AgentTool.ProgressListener delegate, Object lock)
            implements AgentTool.ProgressListener {

        @Override
        public void onProgress(String message) {
            synchronized (lock) {
                delegate.onProgress(message);
            }
        }

        @Override
        public void onToolDelta(String delta) {
            synchronized (lock) {
                delegate.onToolDelta(delta);
            }
        }

        @Override
        public void onModelUsage(com.xander.lab.common.ModelUsage usage, String model) {
            synchronized (lock) {
                delegate.onModelUsage(usage, model);
            }
        }

        @Override
        public void bindInvocationId(String invocationId) {
            // 装饰器必须转交：接口新增的方法被装饰器悄悄吞掉，表现是"事件少了配对键"，
            // 而不是任何异常——排查成本远高于这三行。
            delegate.bindInvocationId(invocationId);
        }

        @Override
        public boolean isCancellationRequested() {
            return delegate.isCancellationRequested();
        }

        @Override
        public void checkCancellation() {
            delegate.checkCancellation();
        }
    }
}

/**
 * 批量执行里一次调用的结果。
 *
 * @param call         模型点名的调用
 * @param invocationId 日志里的稳定身份
 * @param result       成功时的结构化结果
 * @param failure      失败时的异常；与 {@code result} 互斥
 */
record ExecutedToolCall(AgentToolCall call, String invocationId, JsonNode result, Exception failure) {

    boolean failed() {
        return failure != null;
    }
}
