package com.xander.lab.automation.spi;

import java.time.Instant;

/**
 * 一条定时邮件提醒的服务间读模型（V9 §11 / §18 / §91）。
 *
 * <h2>为什么是收窄，不是 {@code EmailReminderTaskVO} 的镜像</h2>
 * <p>镜像规则（{@code docs/migration/adr-contract-boundaries.md} §2.5）适用于一个类型
 * <b>替换</b>了前端可见类型的情形 —— 那时"序列化逐字节不变"是一个可以证明的命题，
 * 它让这次改动被证明是纯重构。本类型不替换任何东西：
 * {@code automation.api.EmailReminderTaskVO} 留在原地，
 * {@code EmailReminderController} 照旧把它发给前端。本类型是<b>新的、在一条新缝上</b>的
 * 读模型，前端永远看不到它。</p>
 *
 * <p>所以它只带三个智能体工具真正读到的字段，每个字段都有具名消费者：</p>
 * <ul>
 *   <li>{@code EmailReminderTool} → {@code id} / {@code recipientEmail} / {@code subject} /
 *       {@code message} / {@code frequency} / {@code scheduledAt} / {@code status}</li>
 *   <li>{@code QueryEmailRemindersTool} → 上述再加 {@code timezone} / {@code sendTime} /
 *       {@code sentAt} / {@code errorMessage}</li>
 *   <li>{@code UpdateEmailReminderTool} → {@code recipientEmail} / {@code subject} /
 *       {@code scheduledAt} / {@code status}</li>
 * </ul>
 *
 * <p>被有意留在外面的字段（{@code senderEmail} / {@code clientRequestId} /
 * {@code contentType} / {@code templateId} / {@code recurrenceDay} / {@code intervalDays} /
 * {@code sendAttempts} / {@code createdAt} / {@code updatedAt}）都是前端页面要的、
 * 智能体不读的。真需要时再加 —— 往契约加字段是一次契约变更，不该靠"先镜像一份省得以后麻烦"
 * 预支。</p>
 *
 * <h2>⚠️ 分量顺序是位置契约，且没有编译期保护</h2>
 * <p>{@code recipientEmail} / {@code subject} / {@code message} / {@code frequency} /
 * {@code timezone} / {@code sendTime} 是<b>六个相邻的 {@code String}</b>，
 * 中间还夹着 {@code scheduledAt} 与 {@code sentAt} 两个 {@code Instant}。
 * 重排不会有任何编译错误，只会静默换字段（把收件人当主题发出去）。
 * 分量名与顺序由 {@code RecordShapeTest} 反射钉住；
 * {@code EmailReminderService} 的 VO → 本类型换算由应用模块的测试用<b>互不相同的字面量</b>
 * 逐字段钉住 —— 只给"看起来对"的值的话，把两个分量对调测试仍然全绿。</p>
 *
 * @param id             任务主键
 * @param recipientEmail 收件人邮箱
 * @param subject        邮件主题
 * @param message        邮件正文（用户原样输入，未渲染）
 * @param frequency      重复频率：{@code ONCE} / {@code DAILY} / {@code WEEKLY} /
 *                       {@code MONTHLY} / {@code CUSTOM}
 * @param timezone       计划时区，如 {@code Asia/Shanghai}
 * @param sendTime       计划发送的本地时刻，形如 {@code 09:30}；{@code ONCE} 频率下可为 {@code null}
 * @param scheduledAt    下一次发送时间（绝对时刻）
 * @param status         任务状态，取值见 {@link EmailReminderPort} 上的常量
 * @param errorMessage   最近一次失败原因；成功或未发送时为 {@code null}
 * @param sentAt         最近一次发送成功时间；未发送时为 {@code null}
 */
public record EmailReminderView(
        Long id,
        String recipientEmail,
        String subject,
        String message,
        String frequency,
        String timezone,
        String sendTime,
        Instant scheduledAt,
        String status,
        String errorMessage,
        Instant sentAt) {
}
