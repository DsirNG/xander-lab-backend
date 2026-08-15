package com.xander.lab.admin.api;

import com.xander.lab.common.PageData;
import com.xander.lab.common.Result;
import com.xander.lab.identity.api.UserAdminView;
import com.xander.lab.identity.application.UserAdminService;
import com.xander.lab.identity.security.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理台用户封禁/解封/查询。
 * 路由前缀 /api/admin 由身份模块拦截器强制「登录 + ADMIN 角色」。
 */
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final UserAdminService userAdminService;

    /** 分页查询用户，支持用户名/昵称/邮箱关键字过滤。 */
    @GetMapping
    public Result<PageData<UserAdminView>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        return Result.success(userAdminService.listUsers(keyword, page, size));
    }

    /** 封禁（status=0，立即撤销全部会话）或解封（status=1）。 */
    @PatchMapping("/{id}/status")
    public Result<UserAdminView> updateStatus(@PathVariable Long id,
                                              @RequestBody UpdateUserStatusRequest request) {
        return Result.success(userAdminService.setUserStatus(UserContext.getUserId(), id, request.status()));
    }
}