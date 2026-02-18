package com.xander.lab.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统用户实体
 */
@Data
@TableName("sys_user")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户名 */
    private String username;

    /** 密码（加密存储，模拟环境先存明文） */
    private String password;

    /** 昵称 */
    private String nickname;

    /** 邮箱（用于验证码登录） */
    private String email;

    /** 头像 URL */
    private String avatar;

    /** 角色：ADMIN, USER, GUEST */
    private String role;

    /** 状态：1=启用, 0=禁用 */
    private Integer status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
