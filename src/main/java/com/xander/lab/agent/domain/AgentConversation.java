package com.xander.lab.agent.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** A durable chat conversation between the user and the orchestration agent. */
@Data
@TableName("agent_conversation")
public class AgentConversation {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String title;
    /**
     * 会话状态——**权威状态 {@link AgentRun#getStatus()} 的投影**，不是可以就地拼的字符串。
     *
     * <p>取值就是 {@link AgentRunStatusProjection} 里那 7 个：{@code ready} / {@code running} /
     * {@code awaiting_approval} / {@code paused} / {@code interrupted} / {@code budget_exhausted} /
     * {@code failed}。它是**前端契约**，同时被 {@code claim()} 的条件更新、租约续期、
     * 取消轮询当作前置条件读，所以写入只能经 {@code AgentRunStatusProjection.of(...)} 算出来，
     * 不要在本类之外手写字面量。</p>
     *
     * <p>投影不是单射：{@code COMPLETED} 与 {@code CANCELLED} 都投影成 {@code ready}
     * （"模型做完了"和"我按了停止"对用户来说是同一个后续动作），想知道真实原因要读
     * {@code agent_run.status}。</p>
     */
    private String status;
    private String errorMessage;
    /** 当前执行代次；每次成功 claim 后递增，用于隔离旧 worker 的迟到写入。 */
    private Long runVersion;
    /** 用户取消请求标记：0=未请求，1=执行中轮询到后立即停止；新一轮开始时复位。 */
    private Integer cancelRequested;
    /** 智能体自主维护的任务计划快照（JSON 数组），是会话级状态而非日志。 */
    private String planJson;
    /** 分享令牌，启用分享时生成 UUID，禁用时置 NULL。 */
    private String shareToken;
    /** 置顶标记：0=普通会话，1=置顶会话；列表先按置顶分组，组内再按更新时间倒序。 */
    private Integer isPinned;
    /** 用户最近一次打开该会话的时间；早于 lastCompletedAt 即表示有未读的生成结果。 */
    private LocalDateTime lastReadAt;
    /** 最近一轮执行到达终态（成功收口、失败、中断收口）的时间，是未读提醒的事实来源。 */
    private LocalDateTime lastCompletedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    /**
     * 派生字段：本轮生成已完成但用户尚未打开过（lastCompletedAt 晚于 lastReadAt）。
     *
     * <p>不落库，由应用层在读取时按两个时间戳算出，避免多端阅读时布尔标记漂移。</p>
     */
    @TableField(exist = false)
    private Boolean unread;
}
