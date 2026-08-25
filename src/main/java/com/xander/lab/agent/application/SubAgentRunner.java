package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.xander.lab.agent.infrastructure.AgentConversationProperties;
import com.xander.lab.agent.infrastructure.AgentModelClient;
import com.xander.lab.common.ModelCallResult;
import com.xander.lab.common.ModelUsage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;

/**
 * Multi-Agent：把一个能独立收口的子任务交给一个上下文隔离的子智能体去跑。
 *
 * <p>为什么需要它：主循环的上下文里塞着完整对话、计划、记忆和历史观察，
 * 一个"翻遍知识库再整理"的子任务如果就地做，十几条原始工具输出会把主上下文冲垮，
 * 后面的推理反而变差。子智能体只看到一份自包含的任务简报，自己跑自己的小循环，
 * 最后只把结论交回主智能体——脏活的中间产物留在它自己的上下文里。</p>
 *
 * <p>子智能体不是"另一个人格"，而是同一套决策协议的一次受限执行：步数预算更小、
 * 工具集更窄、不能再往下派发、不能改写主会话的任务计划。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubAgentRunner {

    /** 子任务目标的长度上限；再长说明该拆成多个子任务而不是写一篇需求文档。 */
    static final int MAX_OBJECTIVE_LENGTH = 300;
    /** 背景简报的长度上限：够自包含就行，超出部分截断。 */
    static final int MAX_BRIEF_LENGTH = 2000;
    /** 单条观察在子上下文里的保留长度。 */
    private static final int OBSERVATION_CHARS = 1200;
    /** 交回主智能体的结论长度上限，避免子任务反过来灌满主上下文。 */
    static final int MAX_FINDINGS_LENGTH = 4000;
    /** 子智能体连续失败多少次就收工，如实交代而不是把预算烧完。 */
    private static final int MAX_FAILURES = 2;

    /**
     * 子智能体拿不到的工具。
     *
     * <p>{@code delegate_subtask} 会递归派发让预算失控；{@code plan_tasks} 是主会话状态，
     * 子智能体改它等于绕过主智能体的自主安排，反思门禁也会读到不属于自己的计划。</p>
     */
    static final Set<String> FORBIDDEN_TOOLS = Set.of(DelegateSubtaskTool.NAME, PlanTasksTool.NAME);

    // 注册表按 List<AgentTool> 收集全部工具，其中包含派发工具本身；用 ObjectProvider 延迟取用打断构造期循环。
    private final ObjectProvider<AgentToolRegistry> registryProvider;
    private final AgentModelClient modelClient;
    private final AgentConversationProperties properties;

    /**
     * 跑一个子任务。
     *
     * @param context        主会话的执行上下文，子智能体的工具沿用它（userId 与代次不变）
     * @param objective      子任务目标
     * @param brief          主智能体提供的背景，子智能体看不到对话历史，所以这里必须自包含
     * @param requestedTools 主智能体希望限定的工具名；为空表示交给子智能体在允许集合里自己挑
     * @param onProgress     主循环的进度监听；用于转发阶段提示、回报用量和响应取消
     */
    public SubAgentResult run(AgentToolContext context, String objective, String brief,
                              Set<String> requestedTools, AgentTool.ProgressListener onProgress) {
        String goal = limit(objective, MAX_OBJECTIVE_LENGTH);
        if (!StringUtils.hasText(goal)) {
            throw new IllegalArgumentException("objective 不能为空");
        }
        AgentToolRegistry registry = registryProvider.getObject();
        Set<String> allowed = resolveTools(registry, requestedTools);
        String toolDescriptions = registry.describeTools(allowed);
        int maxSteps = Math.max(1, properties.getMaxSubAgentSteps());

        List<String> observations = new ArrayList<>();
        List<String> toolCalls = new ArrayList<>();
        UsageTally tally = new UsageTally();
        String findings = "";
        boolean completed = false;
        int failures = 0;
        int step = 0;

        onProgress.onProgress("子任务|" + goal);
        while (step < maxSteps) {
            onProgress.checkCancellation();
            step++;
            ModelCallResult call = modelClient.decideNextStepStream(
                    buildContext(goal, brief, observations, maxSteps - step),
                    toolDescriptions,
                    // 子智能体的回复是给主智能体看的观察，不能当成用户可见的流式答复。
                    ignored -> { },
                    onProgress::isCancellationRequested);
            tally.add(call.usage(), call.model());
            JsonNode decision = call.payload();

            if (decision.path("done").asBoolean(false)) {
                findings = limit(decision.path("reply").asText(""), MAX_FINDINGS_LENGTH);
                completed = StringUtils.hasText(findings);
                break;
            }

            String toolName = decision.path("action").path("tool").asText("");
            AgentTool tool = allowed.contains(toolName) ? registry.get(toolName) : null;
            if (tool == null) {
                observations.add("尝试调用 " + describeUnknown(toolName) + " 被拒绝：不在本子任务的可用工具内（"
                        + String.join(", ", allowed) + "）");
                continue;
            }

            toolCalls.add(toolName);
            onProgress.onProgress("子任务|" + goal + " · " + toolName);
            try {
                JsonNode result = tool.execute(context, decision.path("action").path("args"),
                        new QuietProgress(onProgress));
                observations.add("调用 " + toolName + " 得到：" + limit(result.toString(), OBSERVATION_CHARS));
                failures = 0;
            } catch (CancellationException cancellation) {
                throw cancellation;
            } catch (Exception error) {
                failures++;
                observations.add("调用 " + toolName + " 失败：" + safeMessage(error));
                if (failures >= MAX_FAILURES) break;
            }
        }

        if (!completed && !StringUtils.hasText(findings)) {
            findings = observations.isEmpty()
                    ? "子任务没有产出任何观察。"
                    : limit(String.join("\n", observations), MAX_FINDINGS_LENGTH);
        }
        onProgress.onModelUsage(tally.sum(), tally.model());
        log.info("agent subtask finished conversation={} steps={} tools={} completed={}",
                context.conversationId(), step, toolCalls.size(), completed);
        return new SubAgentResult(goal, findings, step, List.copyOf(toolCalls), completed,
                List.copyOf(allowed));
    }

    /** 子任务的产出。findings 是交回主智能体的结论，completed=false 时它只是中途观察。 */
    public record SubAgentResult(String objective, String findings, int steps,
                                 List<String> toolCalls, boolean completed, List<String> allowedTools) {
    }

    private Set<String> resolveTools(AgentToolRegistry registry, Set<String> requested) {
        Set<String> available = new LinkedHashSet<>(registry.names());
        available.removeAll(FORBIDDEN_TOOLS);
        // 注册表按名字排序，保持插入序让工具表与拒绝提示每次渲染都一致（便于上游 prompt 缓存）。
        if (requested == null || requested.isEmpty()) return Collections.unmodifiableSet(available);
        // 主智能体给的白名单只能收窄不能放宽：不认识的名字直接忽略，全都不认识就退回完整允许集。
        Set<String> narrowed = new LinkedHashSet<>(available);
        narrowed.retainAll(requested);
        return Collections.unmodifiableSet(narrowed.isEmpty() ? available : narrowed);
    }

    private String buildContext(String objective, String brief, List<String> observations, int remainingSteps) {
        StringBuilder builder = new StringBuilder();
        builder.append("你是主智能体派出的子智能体，只负责完成下面这一个子任务，不要和用户对话。\n\n");
        builder.append("【子任务目标】").append(objective).append("\n\n");
        if (StringUtils.hasText(brief)) {
            builder.append("【主智能体给的背景】").append(limit(brief, MAX_BRIEF_LENGTH)).append("\n\n");
        }
        if (observations.isEmpty()) {
            builder.append("【已有观察】还没有。\n\n");
        } else {
            builder.append("【已有观察】\n");
            for (int index = 0; index < observations.size(); index++) {
                builder.append(index + 1).append(") ").append(observations.get(index)).append('\n');
            }
            builder.append('\n');
        }
        builder.append("剩余可用步数：").append(Math.max(0, remainingSteps)).append("。\n");
        builder.append("还需要取证就返回 action 调用工具；已经拿到足够信息就返回 done=true，"
                + "reply 写成给主智能体看的结论：列出关键事实、数据与出处，"
                + "没查到的部分如实说明，不要客套、不要写给用户看的开场白。");
        return builder.toString();
    }

    private String describeUnknown(String toolName) {
        return StringUtils.hasText(toolName) ? toolName : "（空工具名）";
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        return StringUtils.hasText(message) ? limit(message, 200) : error.getClass().getSimpleName();
    }

    private String limit(String value, int maxLength) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength) + "…";
    }

    /** 子智能体内部工具的草稿输出不外泄给用户：那是过程产物，不是主智能体的答复。 */
    private record QuietProgress(AgentTool.ProgressListener delegate) implements AgentTool.ProgressListener {
        @Override
        public void onProgress(String message) {
            delegate.onProgress(message);
        }

        @Override
        public boolean isCancellationRequested() {
            return delegate.isCancellationRequested();
        }
    }

    /** 汇总子智能体自己发起的模型调用用量，混用模型时不上报模型名以免计价选错。 */
    private static final class UsageTally {
        private long input;
        private long cached;
        private long output;
        private String model;
        private boolean mixed;

        void add(ModelUsage usage, String usedModel) {
            if (usage != null) {
                input += Math.max(0L, usage.inputTokens());
                cached += Math.max(0L, usage.cachedTokens());
                output += Math.max(0L, usage.outputTokens());
            }
            if (StringUtils.hasText(usedModel)) {
                if (model == null) model = usedModel;
                else if (!model.equals(usedModel)) mixed = true;
            }
        }

        ModelUsage sum() {
            return new ModelUsage(input, cached, output);
        }

        String model() {
            return mixed ? null : model;
        }
    }
}
