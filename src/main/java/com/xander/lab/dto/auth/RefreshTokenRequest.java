package com.xander.lab.dto.auth;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Token 刷新请求体
 * 对应前端 axios 封装中的 refreshAccessToken() 调用：
 *   POST /auth/refresh  { refreshToken: "..." }
 */
@Data
public class RefreshTokenRequest {

    @NotBlank(message = "refreshToken 不能为空")
    private String refreshToken;
}
