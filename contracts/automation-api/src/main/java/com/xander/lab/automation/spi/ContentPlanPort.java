package com.xander.lab.automation.spi;

import java.util.List;

/**
 * 定时发文计划能力的服务间端口。
 *
 * <p>由 <b>automation</b> 域实现（{@code BlogContentPlanService}），由 <b>agent</b> 域消费
 * （{@code QueryPlansTool} / {@code SchedulePlanTool} / {@code UpdatePlanTool}）。</p>
 *
 * <h2>方法集为什么正好是这六个</h2>
 * <p>实现方对<b>自己的控制器</b>公开了 15 个方法（{@code aiGenerate} /
 * {@code createOneShotPlans} / {@code list} / {@code detail} / {@code trigger} /
 * {@code listRuns} / {@code runDetail} / {@code generateTopics} / {@code nextRunAfter} …），
 * 端口只要其中六个。这不是"少写几个无所谓"：端口上每一个方法都是永久暴露面，
 * 而<b>端口变宽没有任何门禁在守</b>（实测结论：
 * 给端口加一个 {@code default} 方法后编译通过、指标不动、26 条门禁全绿，
 * 只有反射测试会红）。所以反射测试钉住<b>完整签名</b>，而不只是方法个数。</p>
 *
 * <h2>端口只回答"问题"，不替调用方决定"显示策略"</h2>
 * <p>六个方法都只做数据往返。{@code {"ok":true,"plans":[…],"hint":"…"}} 那个输出形状是
 * {@code QueryPlansTool} 自己的事：它要按模型可读的方式重排字段、把 {@code null} 变成
 * 空串、加一句中文提示。放进端口就会让 automation 承担"智能体工具返回格式"的责任，
 * 而 automation 不认识 {@code AgentTool} 这个协议。</p>
 *
 * <h2>⚠️ 已知残留：异常类型仍然是框架类型</h2>
 * <p>与 {@link EmailReminderPort} 同一处：实现方用 {@code ResponseStatusException}
 * 表达"未登录 / 不存在 / 状态冲突"，那是前端 HTTP 状态码的来源，不能顺手改。
 * 跨进程时这一层要重新设计。</p>
 */
public interface ContentPlanPort {

    /**
     * 当前用户的全部定时发文计划，<b>不分页</b>；可按状态筛选。
     *
     * @param userId 调用方身份，必须显式传入
     * @param status 状态筛选；{@code null} / 空串 / {@code "ALL"} 表示全部
     * @return 计划列表；无匹配时返回空列表，不返回 {@code null}
     */
    List<ContentPlanView> listAll(Long userId, String status);

    /**
     * 创建一条定时发文计划。
     *
     * <p>带幂等语义：{@code draft.clientRequestId()} 非空且已存在计划时返回既有计划。</p>
     */
    ContentPlanView create(Long userId, ContentPlanDraft draft);

    /**
     * 按幂等键查已有创建结果，供调用方在重试后确认"上一次到底成没成"。
     *
     * @return 命中的计划；未命中时返回 {@code null}
     */
    ContentPlanView findByClientRequestId(Long userId, String clientRequestId);

    /**
     * 按动作字符串改状态（暂停 / 恢复 / 取消 / 立即运行等）。
     *
     * @param action 动作名，取值由实现方解释；无法识别时实现方拒绝
     * @return 变更后的计划
     */
    ContentPlanView updateStatus(Long userId, Long planId, String action);

    /**
     * 修改计划字段；{@link ContentPlanEdit} 里为 {@code null} 的字段保持原值。
     *
     * @return 变更后的计划
     */
    ContentPlanView update(Long userId, Long planId, ContentPlanEdit edit);

    /** 删除一条计划。 */
    void delete(Long userId, Long planId);
}
