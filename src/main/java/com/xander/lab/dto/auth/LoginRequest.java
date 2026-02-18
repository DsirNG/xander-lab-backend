package com.xander.lab.dto.auth;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 登录请求体
 * 增加 type 字段区分密码登录和验证码登录
 */
@Data
public class LoginRequest {

    /** 登录方式: password 或 code */
    @NotBlank(message = "登录类型不能为空")
    private String type = "password";

    /** 账号: 用户名或邮箱 */
    @NotBlank(message = "账号不能为空")
    private String account;

    /** 密码（密码登录时必传） */
    private String password;

    /** 验证码（验证码登录时必传） */
    private String code;
}
