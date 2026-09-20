package com.xander.lab.automation.spi;

import java.util.List;

/**
 * 创建一条定时发文计划时要提交的字段。
 *
 * <p>字段集是 {@code SchedulePlanTool} 实际会填的那些。域侧的
 * {@code BlogContentPlanCreateRequest} 还有 {@code scheduleType} / {@code scheduledDate} /
 * {@code aiOption} / {@code knowledgeMaterialId} / {@code autoPublish} —— 那些是前端
 * "AI 生成计划"表单要的，该工具不填，所以不在这里。</p>
 *
 * <h2>⚠️ 分量顺序是位置契约</h2>
 * <p>{@code topic} / {@code triggerTime} / {@code timezone} / {@code audience} /
 * {@code tone} 都是 {@code String}，{@code topics} 与 {@code triggerTimes} 都是
 * {@code List&lt;String&gt;}，两个 {@code Boolean} 相邻。
 * 记录类型靠位置构造，"参数写反"不会有编译错误。
 * 分量名与顺序由 {@code RecordShapeTest} 反射钉住；
 * 换算到 {@code BlogContentPlanCreateRequest} 的落点由 {@code BlogContentPlanServiceTest}
 * 用互不相同的字面量逐字段钉住。</p>
 *
 * @param clientRequestId 幂等键，取自调用方的 {@code invocationId}；可为 {@code null}
 * @param topic           计划主题（必填）
 * @param topics          主题候选池；与 {@code topic} 二者至少一个非空
 * @param triggerTimes    每日触发时间列表，格式 {@code HH:mm}
 * @param triggerTime     单个触发时间；仅在 {@code triggerTimes} 为空时使用
 * @param timezone        计划时区
 * @param audience        目标读者描述，参与生成提示词
 * @param tone            语气风格描述，参与生成提示词
 * @param syncCsdn        是否同步到 CSDN
 * @param syncJuejin      是否同步到掘金
 */
public record ContentPlanDraft(
        String clientRequestId,
        String topic,
        List<String> topics,
        List<String> triggerTimes,
        String triggerTime,
        String timezone,
        String audience,
        String tone,
        Boolean syncCsdn,
        Boolean syncJuejin) {
}
