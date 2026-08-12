package com.xander.lab.billing.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户积分账户。
 *
 * <p>{@code user_id} 即主键；余额允许暂为负值（token 计费在成功完成后扣，
 * 若某次运行成本超出余额会产生少量欠费），由运营策略兜底。</p>
 */
@Data
@TableName("sys_user_points")
public class PointAccount {

    @TableId(value = "user_id", type = IdType.INPUT)
    private Long userId;

    /** 当前余额（积分） */
    private Long balance;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}