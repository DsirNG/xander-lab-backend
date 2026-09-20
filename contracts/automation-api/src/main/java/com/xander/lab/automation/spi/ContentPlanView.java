package com.xander.lab.automation.spi;

import java.time.Instant;
import java.util.List;

/**
 * 一条定时发文计划的服务间读模型。
 *
 * <h2>为什么是收窄，不是 {@code BlogContentPlanVO} 的镜像</h2>
 * <p>理由与 {@link EmailReminderView} 相同，但这里的差距更大：{@code BlogContentPlanVO}
 * 有 24 个字段，其中包含四组相邻同类型分量，而本记录只带三个工具真正读到的 11 个。
 * 镜像它意味着照抄一个位置顺序风险很大的 24 分量记录，去保留一个<b>不存在</b>的序列化表面 ——
 * {@code BlogContentPlanVO} 继续留在 {@code automation.api}，继续由
 * {@code BlogContentPlanController} 发给前端，本类型前端看不到。
 * </p>
 *
 * <p>被有意留在外面的字段：{@code topics} / {@code nextTopicIndex} / {@code audience} /
 * {@code tone} / {@code scheduleType} / {@code scheduledDate} / {@code aiOption} /
 * {@code knowledgeMaterialId} / {@code autoPublish} / {@code triggerTime} /
 * {@code createdAt} / {@code updatedAt} / {@code csdnAuthorized} / {@code juejinAuthorized}。
 * 这些是前端计划列表与详情页要的，三个智能体工具一个都不读。</p>
 *
 * <h2>⚠️ 分量顺序是位置契约</h2>
 * <p>本记录里 {@code topic} / {@code status} / {@code timezone} / {@code errorMessage}
 * 都是 {@code String}，三个 {@code Boolean} 相邻，两个 {@code Instant} 相邻。
 * 分量名与顺序由 {@code RecordShapeTest} 反射钉住；
 * {@code BlogContentPlanService} 的 VO → 本类型换算由应用模块的测试用
 * <b>互不相同的字面量</b>逐字段钉住。</p>
 *
 * @param id           计划主键
 * @param topic        计划主题
 * @param status       计划状态：{@code ACTIVE} / {@code PAUSED} / {@code RUNNING} /
 *                     {@code FINISHED} / {@code FAILED} / {@code CANCELLED}
 * @param timezone     计划时区；触发时间按它解释
 * @param triggerTimes 每日触发时间列表，格式 {@code HH:mm}
 * @param syncCsdn     是否同步到 CSDN
 * @param syncJuejin   是否同步到掘金
 * @param runOnce      一次性计划：只触发一次然后自动结束
 * @param nextRunAt    下一次触发时刻；已结束或未排期时为 {@code null}
 * @param lastRunAt    最近一次触发时刻；从未触发时为 {@code null}
 * @param errorMessage 最近一次运行失败原因；正常时为 {@code null}
 */
public record ContentPlanView(
        Long id,
        String topic,
        String status,
        String timezone,
        List<String> triggerTimes,
        Boolean syncCsdn,
        Boolean syncJuejin,
        Boolean runOnce,
        Instant nextRunAt,
        Instant lastRunAt,
        String errorMessage) {
}
