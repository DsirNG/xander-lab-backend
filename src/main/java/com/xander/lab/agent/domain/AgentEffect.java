package com.xander.lab.agent.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一个**业务动作**的身份：一次业务动作可能对应多次物理调用。
 *
 * <p><b>它解决什么。</b>{@link AgentToolInvocation#getInvocationId()} 每次调用都是新 UUID，
 * 而它被写工具当下游幂等键。于是"模型看到 {@code cancelled} 之后重新发起同一个调用"会带一个新键，
 * 下游 {@code UNIQUE (user_id, client_request_id)} 不命中，**真会执行第二次**。{@code effectId}
 * 跨多次 invocation 保持不变，重试因此被下游认成同一件事。</p>
 *
 * <p><b>与 {@code invocationId} 是两个层次的事实，不要合并。</b>那个回答"这一次物理调用是哪一次"
 * （一次 effect 可以有很多次），这个回答"这是不是同一个业务动作"（一次业务动作只该有一个）。</p>
 *
 * <p><b>采纳规则</b>（谁在什么时候复用既有 Effect）见 {@code AgentEffectService#resolveEffect}。
 * 这里只说明状态的生命周期：</p>
 *
 * <ul>
 *   <li>{@link #STATUS_OPEN}——已提出、还没拿到确认。**只有它能被采纳**。</li>
 *   <li>{@link #STATUS_SETTLED}——已有确认成功的调用。不再采纳，否则会吞掉用户真的要做的第二次。</li>
 *   <li>{@link #STATUS_ABANDONED}——已放弃（审批被拒）。不再采纳。</li>
 * </ul>
 *
 * <p><b>取消不结算 Effect</b>：取消结算的是 invocation（{@code CANCELLED}），Effect 保持
 * {@code OPEN}。这是语义上正确的分工——"这一次执行停了"不等于"这个业务动作不打算做了"，
 * 而后者正是重试要接上的东西。</p>
 */
@Data
@TableName("agent_effect")
public class AgentEffect {
    /** 已提出、未确认完成；**唯一可被采纳**的状态。 */
    public static final String STATUS_OPEN = "OPEN";
    /** 已有确认成功的调用；不再采纳。 */
    public static final String STATUS_SETTLED = "SETTLED";
    /** 已放弃（如审批被拒）；不再采纳。 */
    public static final String STATUS_ABANDONED = "ABANDONED";

    @TableId(type = IdType.AUTO)
    private Long id;
    /**
     * 业务幂等身份（UUID），Runtime 铸造。
     *
     * <p>它是下游 {@code client_request_id} 的**取值来源**。之所以不复用
     * {@code invocationId}：那个的语义是"这一次调用"，而重试恰恰是"另一次调用、同一件事"。</p>
     */
    private String effectId;
    /** 归属用户；与下游唯一键 {@code (user_id, client_request_id)} 的组成对齐。 */
    private Long userId;
    private Long conversationId;
    /** 首次提出该副作用的执行记录；后续复用它的调用属于同一个 Run。 */
    private Long runId;
    /**
     * 所属用户意图周期，与 {@code agent_run.intent_epoch} 同源。
     *
     * <p>采纳判定**只在本周期内进行**。跨周期的一律是新动作——否则用户真的要发第二条提醒时，
     * 会被当成"对第一条的重试"而吞掉。</p>
     */
    private String intentEpoch;
    private String toolName;
    /**
     * 工具名与归一化参数的 SHA-256。
     *
     * <p>**只用于漂移检测与告警，不作为身份**。把参数当身份会让同义改写与合法内容变更都判错。</p>
     */
    private String argsFingerprint;
    /** 首次提出时的参数原文，用于漂移时的人工排查；不保存凭据。 */
    private String argsJson;
    /**
     * 首次提出该副作用所在的模型步序号。
     *
     * <p>采纳规则要求"创建于更早的 step"：同一批次里的两次相同调用是**两个有意的动作**
     * （用户要发两条提醒），不该互相采纳。</p>
     */
    private Integer createdStepNo;
    /** 远端返回的稳定业务标识；拿到它就等于拿到了对账句柄。 */
    private String externalId;
    /** OPEN / SETTLED / ABANDONED，见本类常量。 */
    private String status;
    private LocalDateTime createdAt;
    /** 进入 SETTLED 或 ABANDONED 的时间；OPEN 期间为空。 */
    private LocalDateTime settledAt;

    /** 是否可以被新调用采纳。 */
    public boolean isAdoptable() {
        return STATUS_OPEN.equals(status);
    }
}
