package com.xander.lab.agent.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Durable identity and outcome of one model-requested tool execution. */
@Data
@TableName("agent_tool_invocation")
public class AgentToolInvocation {
    public static final String STATUS_RUNNING = "RUNNING";
    /** Invocation is durable and bound to an approval, but its tool has not run. */
    public static final String STATUS_WAITING_APPROVAL = "WAITING_APPROVAL";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    /** The worker ended before the remote side effect could be reconciled. */
    public static final String STATUS_UNKNOWN = "UNKNOWN";

    /**
     * 没有进入副作用路径：只读工具（没有 {@code effect_id}），或**迁移前落库的存量行**。
     *
     * <p><b>读侧不能只凭这个值就判定"远端什么都没发生"。</b>阶段列是 B7 才加的，存量行保持默认
     * {@code NONE}；而"已经有 {@code effect_id} 却停在 {@code NONE}"只可能出自 B6 与 B7 之间的
     * 部署窗口——那时请求可能已经发出去了，只是没留下任何阶段证据。所以判"能否安全终结"时要
     * **连 {@code effect_id} 一起看**：没有 {@code effect_id} 才是"压根没有远端写"的确证。
     * 见 {@code AgentExecutionJournal#settleInFlightWork}。</p>
     */
    public static final String EFFECT_STATE_NONE = "NONE";
    /**
     * 意图已落库、但请求还没有交给工具。
     *
     * <p>这个阶段崩溃可以安全地判定"远端什么都没发生"——所以它**不进对账**，
     * 直接按运行的终态结算即可。</p>
     */
    public static final String EFFECT_STATE_PREPARED = "PREPARED";
    /**
     * 请求已交给工具，因此**可能已经在远端生效**。
     *
     * <p>这是唯一需要进入对账的阶段：从这个标记之后，本地就无法再靠状态判断"到底写没写"。
     * 标记本身必须写在把请求交出去**之前**——崩在"标记"与"发送"之间只会多一次无谓的对账
     * （安全方向），反过来则会漏掉一次真实写入（要命方向）。</p>
     */
    public static final String EFFECT_STATE_DISPATCHED = "DISPATCHED";
    /** 已拿到远端成功凭据；与 invocation 的 {@code SUCCEEDED} 同事务落库。 */
    public static final String EFFECT_STATE_CONFIRMED = "CONFIRMED";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long runId;
    private Long stepId;
    /**
     * Identity of this one physical call. Stable within a single execution, but a retry of the same
     * business action gets a new one — so it is <em>not</em> the business idempotency key.
     *
     * @see #effectId
     */
    private String invocationId;
    /**
     * Business idempotency identity shared by every physical call that performs the same action
     * (see {@code AgentEffect}). Null for read-only tools and for runs created before the effect
     * migration. Tool adapters must key their downstream deduplication on this, falling back to
     * {@code invocationId} only when it is absent.
     */
    private String effectId;
    private String toolName;
    /** Model-supplied arguments, retained only inside the private agent journal. */
    private String argumentsJson;
    /** RUNNING/WAITING_APPROVAL/SUCCEEDED/FAILED/CANCELLED/UNKNOWN. */
    private String status;
    /**
     * 副作用执行阶段：NONE/PREPARED/DISPATCHED/CONFIRMED，见本类常量。
     *
     * <p>它回答的是"这次调用走到哪一步了"（动态、按 invocation），与
     * {@code agent_effect} 的"这是不是同一个业务动作"（静态、按意图周期）是两个问题。</p>
     */
    private String effectState;
    /** 标记为 {@code DISPATCHED} 的时间；必须写在把请求交给工具之前。 */
    private LocalDateTime effectDispatchedAt;
    /** 拿到远端成功凭据的时间。 */
    private LocalDateTime effectConfirmedAt;
    private String resultJson;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
