package com.xander.lab.billing.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 积分流水（审计）。
 *
 * <p>每行对应一次 grant/consume/refund，{@code (biz_type, biz_key)} 唯一，
 * 用于保证后台任务不重复扣费。token 计费行同时记录模型与三档 token 明细，
 * 便于对账与复核。</p>
 */
@Data
@TableName("points_ledger")
public class PointsLedger {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属用户，关联 sys_user.id */
    private Long userId;

    /** 业务类型：grant（赠送）/ consume（消耗）/ refund（退款） */
    private String bizType;

    /** 业务幂等键；同一 bizType 下必须唯一 */
    private String bizKey;

    /** 业务引用：taskId / conversationId+runVersion / 计划 runId 等 */
    private String bizRef;

    /** 积分金额（正数），方向由 bizType 决定 */
    private Long amount;

    /** 该笔后余额快照 */
    private Long balanceAfter;

    /** 扣费单价快照（token 单价，单位：毫分/千 token，或一口价时的积分值） */
    private String unitPrice;

    /** token 计费使用的模型名；一口价时为空 */
    private String model;

    /** 输入 token 数 */
    private Long inputTokens;

    /** 输出 token 数 */
    private Long outputTokens;

    /** 缓存命中 token 数 */
    private Long cachedTokens;

    /** 备注 */
    private String remark;

    private LocalDateTime createdAt;
}