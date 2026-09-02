package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.xander.lab.agent.domain.AgentPlanItem;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Evaluator / Reflection：在智能体宣布"做完了"之前做一次确定性自检。
 *
 * <p>刻意不用"再叫一次模型给自己打分"的做法：那既多花一次调用，又容易自我通过。
 * 这里检查的是客观状态——计划里还有没有未收口的步骤、上一次工具是不是失败了却被当成成功。
 * 检出问题时把批评意见回灌到下一步上下文，让智能体自己纠偏；反思次数有预算上限，
 * 避免和模型互相纠缠导致本轮打转。</p>
 */
@Component
public class AgentTurnEvaluator {

    /** 承认失败的措辞；出现其中任一即认为智能体已如实向用户交代，不再拦截。 */
    private static final List<String> FAILURE_ACKNOWLEDGEMENTS = List.of(
            "失败", "未能", "没能", "没有成功", "无法", "不可用", "出错", "错误", "报错",
            "暂时不能", "稍后重试", "重试");

    /**
     * 一条自检结论。
     *
     * <p>带上 {@link Kind} 是为了让调用方能按规则区分对待：计划没收口属于"做得更细"，
     * 只在深度思考模式下才值得多花一轮；工具失败却说成完成属于"回复不诚实"，
     * 任何模式都必须拦，否则用户会收到根本不存在的交付。</p>
     */
    public record Critique(Kind kind, String text) {

        public enum Kind {
            /** 计划里还有步骤没收口。 */
            UNFINISHED_PLAN,
            /** 上一次工具失败了，回复却没交代。 */
            UNACKNOWLEDGED_FAILURE,
        }

        /** 只有"追求完整"类的自检需要深度思考模式；诚实性自检始终生效。 */
        public boolean optionalRefinement() {
            return kind == Kind.UNFINISHED_PLAN;
        }
    }

    /**
     * 判断这一轮能否收口。
     *
     * @param reply     模型准备交给用户的最终回复
     * @param plan      当前任务计划
     * @param lastError 上一次工具失败结果，成功或无调用时为 null
     * @return 需要继续纠偏时返回批评意见，可以收口时返回 empty
     */
    public Optional<Critique> critique(String reply, List<AgentPlanItem> plan, JsonNode lastError) {
        List<AgentPlanItem> unfinished = plan == null ? List.of()
                : plan.stream().filter(item -> !item.settled()).toList();
        if (!unfinished.isEmpty()) {
            StringBuilder builder = new StringBuilder("自检未通过：你的任务计划里还有 ")
                    .append(unfinished.size())
                    .append(" 个步骤没有收口：");
            for (int index = 0; index < unfinished.size(); index++) {
                AgentPlanItem item = unfinished.get(index);
                if (index > 0) builder.append("；");
                builder.append('「').append(item.title()).append("」(").append(item.status()).append(')');
            }
            builder.append("。请继续执行这些步骤；确实不需要做的，用 plan_tasks 把它标成 DROPPED 并在 note 写明原因，"
                    + "然后再给最终回复。");
            return Optional.of(new Critique(Critique.Kind.UNFINISHED_PLAN, builder.toString()));
        }

        if (lastError != null && !acknowledgesFailure(reply)) {
            return Optional.of(new Critique(Critique.Kind.UNACKNOWLEDGED_FAILURE,
                    "自检未通过：上一次工具调用失败了（" + errorText(lastError)
                            + "），但你的回复没有向用户交代这次失败。"
                            + "请要么换一种参数或工具重试，要么在回复里如实说明失败原因和影响，不要把失败说成完成。"));
        }
        return Optional.empty();
    }

    private boolean acknowledgesFailure(String reply) {
        if (reply == null || reply.isBlank()) return false;
        return FAILURE_ACKNOWLEDGEMENTS.stream().anyMatch(reply::contains);
    }

    private String errorText(JsonNode lastError) {
        String message = lastError.path("error").asText("");
        if (!message.isBlank()) return message;
        return lastError.toString();
    }
}
