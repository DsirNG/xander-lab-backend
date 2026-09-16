package com.xander.lab.admin.api;

/** 修改用户状态请求：status=0 封禁（并立即踢下线全部会话），status=1 解封。 */
public record UpdateUserStatusRequest(int status) {
}