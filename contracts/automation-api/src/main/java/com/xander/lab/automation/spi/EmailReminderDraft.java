package com.xander.lab.automation.spi;

import java.time.OffsetDateTime;

/**
 * 创建一条定时邮件提醒时要提交的字段（V9 §11 / §18 / §91）。
 *
 * <p>字段集是 {@code EmailReminderTool} 实际会填的那些，一个不多：
 * 该工具从模型给它的 JSON 里读 {@code recipientEmail} / {@code subject} / {@code message} /
 * {@code frequency} / {@code timezone} / {@code sendTime} / {@code contentType} /
 * {@code templateId} / {@code recurrenceDay} / {@code intervalDays} / {@code scheduledAt}，
 * 再加上幂等键 {@code clientRequestId}（取自 {@code context.invocationId()}）。</p>
 *
 * <h2>为什么没有 {@code jakarta.validation} 注解</h2>
 * <p>域侧的 {@code EmailReminderCreateRequest} 带 {@code @NotBlank} / {@code @Email} /
 * {@code @Pattern} / {@code @Size} / {@code @Min} / {@code @Max}。那些是 <b>HTTP 边界</b>的事：
 * 它们生效是因为控制器把参数标了 {@code @Valid}。契约也不允许带它们（会引入依赖，
 * ADR-1 要求本模块与框架无关）。智能体这条路径上什么都没丢 —— 它本来就没有这些校验：
 * 工具从 JSON 取参数、自己手写必填检查，再调用端口。</p>
 *
 * <h2>⚠️ 分量顺序是位置契约</h2>
 * <p>{@code recipientEmail} / {@code subject} / {@code message} 是三个相邻的 {@code String}，
 * {@code frequency} / {@code timezone} 是两个，{@code contentType} / {@code templateId} 又是两个。
 * 记录类型靠位置构造，所以"参数写反"不会有编译错误。实现方把本类型换算成
 * {@code EmailReminderCreateRequest} 时，{@code EmailReminderServiceTest} 会用
 * <b>互不相同的字面量</b>逐字段钉住落点；分量名与顺序本身由
 * {@code RecordShapeTest} 反射钉住。</p>
 *
 * @param clientRequestId 幂等键，取自调用方的 {@code invocationId}；可为 {@code null}
 * @param recipientEmail  收件人邮箱（必填，实现方拒绝空值）
 * @param subject         邮件主题（必填；实现方拒绝含换行的主题）
 * @param message         邮件正文（必填）
 * @param frequency       重复频率，默认 {@code ONCE}
 * @param timezone        计划时区，默认 {@code Asia/Shanghai}
 * @param scheduledAt     一次性任务的发送时刻；重复任务为 {@code null}
 * @param sendTime        重复任务的每日触发时间 {@code HH:mm}；一次性任务为 {@code null}
 * @param recurrenceDay   周/月重复时的星期或日期；其余频率为 {@code null}
 * @param intervalDays    自定义间隔天数；其余频率为 {@code null}
 * @param contentType     正文类型，{@code PLAIN} 或 {@code HTML}
 * @param templateId      版式模板标识，{@code none} 表示不套版式
 */
public record EmailReminderDraft(
        String clientRequestId,
        String recipientEmail,
        String subject,
        String message,
        String frequency,
        String timezone,
        OffsetDateTime scheduledAt,
        String sendTime,
        Integer recurrenceDay,
        Integer intervalDays,
        String contentType,
        String templateId) {
}
