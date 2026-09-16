package com.xander.lab.identity.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 安全事件事务性发件箱（V9 §27）。
 *
 * <p>管理员封禁的事务里必须原子地完成「写用户状态 + 撤销 Refresh Session +
 * 提升 security_version + 写安全事件」。但"把 accountId 推给每个宿主的本地
 * deny cache""关闭已建立的 SSE / WS"不可能在事务内完成（跨进程），
 * 所以事件先在事务内落库，提交后再投递 —— 这就是封禁能扛住"提交后立刻崩溃"
 * 的原因。</p>
 */
@Data
@TableName("security_event_outbox")
public class SecurityEventOutboxEntry {

    /** 事件类型：账号被封禁 */
    public static final String TYPE_ACCOUNT_DISABLED = "AccountDisabled";
    /** 事件类型：账号被解封 */
    public static final String TYPE_ACCOUNT_ENABLED = "AccountEnabled";
    /** 事件类型：检测到 refresh token 重用 */
    public static final String TYPE_REFRESH_TOKEN_REUSE = "RefreshTokenReuse";

    /** 投递状态：待投递 */
    public static final String STATUS_PENDING = "PENDING";
    /** 投递状态：已投递 */
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    /** 投递状态：投递失败，等待退避后重试 */
    public static final String STATUS_FAILED = "FAILED";

    /**
     * 投递状态：终止投递（超过重试上界，不再自动重试）。
     *
     * <p>存在的理由：{@code claimDue} 领取的是 {@code status IN ('PENDING','FAILED')}，
     * 所以一条<b>永久性</b>失败的事件（例如负载被写坏、事件类型是本实例不认识的新版本）
     * 会以 {@code FAILED} 身份被无限重试 —— 日志里每分钟出现一次的噪声，永远不会自愈。
     * {@code DEAD} 把它从领取集合里摘出去，同时让"这条事件永远不会被投递"成为一个
     * <b>可查询的显式状态</b>，而不是一个被反复重试掩盖的隐性状态。</p>
     *
     * <p>把事件判死是安全的，这正是 §27 选择事务性发件箱的原因：发件箱只负责
     * <b>通知</b>，不负责<b>状态</b>。{@code account_security_state} 才是 SOT，
     * 每个实例的 {@code SecurityStateReconciler} 仍会按 {@code change_seq} 收敛 ——
     * 所以一条 DEAD 事件不会让封禁失效，只会让"快路径"晚 1.5 秒。</p>
     */
    public static final String STATUS_DEAD = "DEAD";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 聚合类型，当前为 ACCOUNT */
    private String aggregateType;

    /** 聚合标识，即 accountId 的字符串形式 */
    private String aggregateId;

    /** 事件类型 */
    private String eventType;

    /** 事件负载 JSON */
    private String payload;

    private String status;

    private Integer attempts;

    private String lastError;

    private LocalDateTime nextAttemptAt;

    @TableField(value = "created_at", insertStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER,
            updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    private LocalDateTime publishedAt;
}
