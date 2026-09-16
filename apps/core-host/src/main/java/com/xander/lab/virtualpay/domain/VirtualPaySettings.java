package com.xander.lab.virtualpay.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 管理台维护的个人主体虚拟支付单行配置，敏感 AppKey 仅保存密文。 */
@Data
@TableName("virtual_pay_settings")
public class VirtualPaySettings {
    @TableId(type = IdType.INPUT)
    private Long id;
    private Boolean enabled;
    private String offerId;
    private String encryptedAppKey;
    private String plusProductId;
    private Integer plusPriceCents;
    private Integer plusDurationDays;
    private String proProductId;
    private Integer proPriceCents;
    private Integer proDurationDays;
    private String ultraProductId;
    private Integer ultraPriceCents;
    private Integer ultraDurationDays;
    private LocalDateTime updatedAt;
}
