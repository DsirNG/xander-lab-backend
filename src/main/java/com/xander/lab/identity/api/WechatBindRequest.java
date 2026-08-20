package com.xander.lab.identity.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 微信绑定邮箱请求体
 * 绑定路径：POST /api/auth/wechat-bind（携带 pendingBindToken）或
 * POST /api/auth/wechat-bind-existing（携带 Authorization 头）。
 */
@Data
public class WechatBindRequest {

    /** 首次微信登录返回的待绑定凭证（登录流程绑定必传；账号设置补绑走 Authorization 头可不传） */
    @Size(min = 1, max = 2048, message = "绑定凭证不合法")
    private String pendingBindToken;

    /** 要绑定的邮箱（即 PC 端账号；已存在同名账号时合并为同一账号） */
    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String email;

    /** 发送到该邮箱的 6 位验证码 */
    @NotBlank(message = "验证码不能为空")
    @Size(min = 6, max = 6, message = "验证码为 6 位数字")
    private String code;
}