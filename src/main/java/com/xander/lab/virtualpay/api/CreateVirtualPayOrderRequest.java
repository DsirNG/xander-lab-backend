package com.xander.lab.virtualpay.api;

import jakarta.validation.constraints.NotBlank;

/** 创建虚拟支付订单请求；商品价格由服务端目录决定，loginCode 用于即时支付身份签名。 */
public record CreateVirtualPayOrderRequest(
        @NotBlank(message = "商品ID不能为空") String productId,
        @NotBlank(message = "微信登录凭证不能为空") String loginCode) {
}
