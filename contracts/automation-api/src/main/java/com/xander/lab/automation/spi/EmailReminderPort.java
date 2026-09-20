package com.xander.lab.automation.spi;

import java.util.List;

/**
 * 定时邮件提醒能力的服务间端口。
 *
 * <p>由 <b>automation</b> 域实现（{@code EmailReminderService}），由 <b>agent</b> 域消费
 * （{@code EmailReminderTool} / {@code QueryEmailRemindersTool} /
 * {@code UpdateEmailReminderTool}）。</p>
 *
 * <h2>为什么在这里，而不在 agent 侧</h2>
 * <p>消费方是 agent，但契约按<b>能力</b>命名，不按"谁先需要"命名：
 * {@code automation-api} 装的是 automation 这个能力对外提供的一切。
 * 这与 {@code content-api} 装 {@code publishing} 的能力、{@code payment-api} 装
 * {@code billing} 的能力是同一个道理。后面 knowledgebase 与 admin 要的两个端口也归这里，
 * 届时是零接线的扩展。</p>
 *
 * <h2>端口只回答"问题"，不替调用方决定"显示策略"</h2>
 * <p>五个方法都只做数据往返，不做文案兜底、不做 JSON 拼装。智能体要的
 * {@code {"ok":true,...}} 输出形状是 {@code EmailReminderTool} 自己的事 ——
 * 把它塞进端口会让"智能体工具的返回格式"变成 automation 域的责任，
 * 而 automation 根本不认识 {@code AgentTool} 这个协议。</p>
 *
 * <h2>方法集为什么正好是这五个</h2>
 * <p>实现方 {@code EmailReminderService} 对<b>自己的控制器</b>公开的方法比这多
 * （{@code list(userId, page, size, status, search)} 等）。分页查询没有进来，
 * 因为三个工具都不分页：{@code listAll} 的存在意义就是"一次调用拿全量"，
 * 这是它当初为智能体加进去的原因（见该方法的注释）。
 * 端口收的是<b>调用方真正用到的</b>那些，多一个都会成为无人使用的暴露面 ——
 * 而端口变宽是没有任何门禁在守的（实测结论：
 * 加一个 {@code default} 方法后 26 条门禁全绿，只有反射测试会红）。</p>
 *
 * <h2>⚠️ 已知残留：异常类型仍然是框架类型</h2>
 * <p>实现方在参数非法、未登录、任务不存在时抛 {@code ResponseStatusException}
 * （{@code org.springframework.web.server}），冲突时也抛它。同进程里这能工作，
 * 而且<b>不能顺手改</b>：那是 HTTP 状态码的来源，换类型会同时改掉前端的响应码
 * （{@code 400} / {@code 401} / {@code 404} / {@code 409} / {@code 429}）。
 * 但端口签名无法表达它，跨进程时更没有 HTTP 状态码可传。
 * 拆服务时这一层要重新设计（把失败表达成契约里的结果类型，而不是异常）。</p>
 */
public interface EmailReminderPort {

    /** 任务待发送：等待调度器到达 {@code scheduledAt}。 */
    String STATUS_PENDING = "PENDING";

    /** 任务已暂停：用户主动暂停，调度器会跳过它。 */
    String STATUS_PAUSED = "PAUSED";

    /**
     * 当前用户的全部定时邮件任务，<b>不分页</b>；{@code status} 与 {@code search} 可组合筛选。
     *
     * @param userId 调用方身份，必须显式传入 —— 端口跨不了 {@code ThreadLocal}
     * @param status 状态筛选；{@code null} / 空串 / {@code "ALL"} 表示全部。
     *               另接受别名 {@code SCHEDULED} / {@code ACTIVE} → {@link #STATUS_PENDING}、
     *               {@code PROCESSING} → {@code SENDING}
     * @param search 关键词，同时匹配主题与收件人；{@code null} 表示不过滤
     * @return 按创建时间倒序的任务列表；无匹配时返回空列表，不返回 {@code null}
     */
    List<EmailReminderView> listAll(Long userId, String status, String search);

    /**
     * 创建一条定时邮件提醒。
     *
     * <p>带幂等语义：{@code draft.clientRequestId()} 非空且已存在任务时，返回既有任务
     * 而不是新建；该幂等键已被一个<b>已删除</b>的任务占用时失败，而不是复用。</p>
     *
     * @return 创建后的任务（新建或幂等命中）
     */
    EmailReminderView create(Long userId, EmailReminderDraft draft);

    /**
     * 按幂等键查已有创建结果，供调用方在重试后确认"上一次到底成没成"。
     *
     * @param clientRequestId 幂等键；{@code null} / 空白时返回 {@code null}
     * @return 命中的任务；未命中或该任务已被删除时返回 {@code null}
     */
    EmailReminderView findByClientRequestId(Long userId, String clientRequestId);

    /**
     * 暂停或恢复一条任务。
     *
     * @param requestedStatus 只接受 {@link #STATUS_PENDING}（恢复）与 {@link #STATUS_PAUSED}（暂停）
     * @return 变更后的任务
     */
    EmailReminderView updateStatus(Long userId, Long taskId, String requestedStatus);

    /**
     * 删除一条任务。
     *
     * <p>正在发送中的任务不允许删除。删除是软删除，被删任务不再出现在
     * {@link #listAll} 与 {@link #findByClientRequestId} 的结果里，
     * 但它的幂等键仍然被占用。</p>
     */
    void delete(Long userId, Long taskId);
}
