package com.xander.lab.automation.spi;

import java.util.List;

/**
 * 修改一条定时发文计划时要提交的字段（V9 §11 / §18 / §91）。
 *
 * <p>与 {@link ContentPlanDraft} 分开是刻意的：创建时要幂等键，修改时不要；
 * 修改可以只改一部分字段（未提交的字段保持原值），所以"哪些字段可以改"本身是一条信息。
 * 域侧的 {@code BlogContentPlanUpdateRequest} 还有 {@code scheduleType} /
 * {@code scheduledDate} / {@code topics} / {@code aiOption} / {@code knowledgeMaterialId} /
 * {@code autoPublish} —— {@code UpdatePlanTool} 不填，所以不在这里。</p>
 *
 * <h2>⚠️ 分量顺序是位置契约</h2>
 * <p>{@code topic} / {@code triggerTime} / {@code timezone} / {@code audience} /
 * {@code tone} 是五个相邻的 {@code String}，末尾两个 {@code Boolean} 相邻。
 * 分量名与顺序由 {@code RecordShapeTest} 反射钉住；
 * 换算到 {@code BlogContentPlanUpdateRequest} 的落点由 {@code BlogContentPlanServiceTest}
 * 用互不相同的字面量逐字段钉住。</p>
 *
 * @param topic        计划主题
 * @param triggerTimes 每日触发时间列表，格式 {@code HH:mm}
 * @param triggerTime  单个触发时间；仅在 {@code triggerTimes} 为空时使用
 * @param timezone     计划时区
 * @param audience     目标读者描述
 * @param tone         语气风格描述
 * @param syncCsdn     是否同步到 CSDN
 * @param syncJuejin   是否同步到掘金
 */
public record ContentPlanEdit(
        String topic,
        List<String> triggerTimes,
        String triggerTime,
        String timezone,
        String audience,
        String tone,
        Boolean syncCsdn,
        Boolean syncJuejin) {
}
