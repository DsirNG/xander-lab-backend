package com.xander.lab.billing.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 模型 token 定价（管理员维护）。
 *
 * <p>单价单位为「毫分 / 每 1K token」（按 1 元 = 10 积分，¥2/M = 20）。计费时按 model 精确匹配
 * enabled=1 的行；未命中回退 application.yml 的默认价。缓存写入单价为预留字段，
 * 当前模型网关 usage 不含缓存写入计量，参与计算时按 0 处理。</p>
 */
@Data
@TableName("billing_model_price")
public class ModelPrice {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 模型名称，与模型网关返回的 model 字段一致（如 gpt-5.6-terra） */
    private String model;

    /** 输入 token 单价：毫分 / 每 1K token */
    @TableField("input_milli_per_1k_token")
    private Long inputMilliPer1kToken;

    /** 输出 token 单价：毫分 / 每 1K token */
    @TableField("output_milli_per_1k_token")
    private Long outputMilliPer1kToken;

    /** 缓存读取 token 单价：毫分 / 每 1K token */
    @TableField("cached_milli_per_1k_token")
    private Long cachedMilliPer1kToken;

    /** 缓存写入 token 单价：毫分 / 每 1K token；当前 usage 无该计量，预留 */
    @TableField("cached_write_milli_per_1k_token")
    private Long cachedWriteMilliPer1kToken;

    /** 是否启用：1=参与计费，0=停用后回退 yml 默认价 */
    private Integer enabled;

    /** 备注：价目来源、生效说明等 */
    private String remark;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 最近修改时间 */
    private LocalDateTime updatedAt;
}
