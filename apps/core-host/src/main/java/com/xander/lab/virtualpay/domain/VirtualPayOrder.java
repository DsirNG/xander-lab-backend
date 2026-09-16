package com.xander.lab.virtualpay.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 本地虚拟支付订单；业务单号和微信单号共同保证通知重放时只发一次权益。 */
@Data
@TableName("virtual_pay_order")
public class VirtualPayOrder {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String outTradeNo;
    private String wxOrderId;
    private Long userId;
    private String openid;
    private String productId;
    private String tier;
    /** 单价，人民币分。 */
    private Integer goodsPrice;
    private Integer quantity;
    private Integer durationDays;
    private String status;
    private LocalDateTime paidAt;
    private LocalDateTime deliveredAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
