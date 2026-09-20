package com.xander.lab.admin.api;

import com.xander.lab.automation.spi.NotificationBroadcastPort;
import com.xander.lab.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理台通知诊断端点。
 * 路由前缀 /api/admin 由身份模块拦截器强制「登录 + ADMIN 角色」，
 * 业务代码不再自行解析 JWT。
 */
@RestController
@RequestMapping("/api/admin/notifications")
@RequiredArgsConstructor
public class AdminNotificationController {

    private final NotificationBroadcastPort broadcastPort;

    /** 向当前在线客户端发送一条瞬时的 SSE 诊断广播，返回接收连接数。 */
    @PatchMapping("/test-broadcast")
    public Result<Integer> testBroadcast() {
        int recipients = broadcastPort.broadcast(
                "SSE_TEST_BROADCAST", "SSE 广播测试", "管理员发起的实时通知测试");
        return Result.success(recipients);
    }
}
