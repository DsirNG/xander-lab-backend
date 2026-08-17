package com.xander.lab.identity.api;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 微信小程序登录请求体
 * 前端通过 Taro.login() 获取临时登录凭证 code 后换取平台 token。
 */
@Data
public class WechatLoginRequest {

    /** 小程序端 login() 临时登录凭证（5 分钟有效，单次使用） */
    @NotBlank(message = "微信登录凭证不能为空")
    private String code;
}