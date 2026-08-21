package com.xander.lab.identity.api;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 账户信息更新请求体（PUT /api/auth/profile）
 * 昵称必填；头像可留空（保留原值），非空时必须为 http(s) 图片地址。
 */
@Data
public class ProfileUpdateRequest {

    @Size(min = 3, max = 30, message = "用户名长度需在 3-30 个字符之间")
    private String username;

    /** 昵称（展示名） */
    @Size(min = 1, max = 30, message = "昵称长度需在 1-30 字符之间")
    private String nickname;

    /** 头像图片 URL；空白表示不修改头像 */
    @Size(max = 255, message = "头像地址过长")
    private String avatar;
}
