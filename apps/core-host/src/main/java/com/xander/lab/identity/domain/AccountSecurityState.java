package com.xander.lab.identity.domain;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 账号安全状态（封禁 SOT）。
 *
 * <p>每个承载流量的宿主（Gateway / core-host / agent / content-automation /
 * studio）都保留一份<b>本地</b>的"已封禁账号集合"，并必须在封禁传播 SLO 内
 * 与这张表收敛。本地缓存按 {@code accountId} 建索引 —— 这也是为什么
 * {@code sid} 现阶段只是审计与关联标识，没有会话级 deny 的消费者。</p>
 */
@Data
@TableName("account_security_state")
public class AccountSecurityState {

    /** 账号 ID，关联 sys_user.id */
    @TableId(value = "account_id", type = IdType.INPUT)
    private Long accountId;

    /** 封禁标记：0=正常，1=已封禁 */
    private Integer disabled;

    /** 账号安全版本号；每次封禁/解封递增 */
    private Long securityVersion;

    /** 全局单调变更游标；per_instance reconciler 按它增量拉取 */
    private Long changeSeq;

    private LocalDateTime disabledAt;

    private String reason;

    @TableField(value = "created_at", insertStrategy = FieldStrategy.NEVER,
            updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", insertStrategy = FieldStrategy.NEVER,
            updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;

    public boolean isDisabled() {
        return disabled != null && disabled != 0;
    }
}
