package com.xander.lab.admin.api;

import com.xander.lab.common.Result;
import com.xander.lab.identity.api.WechatCredentialView;
import com.xander.lab.identity.application.WechatCredentialService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理台微信登录凭据：查询/保存/清空。
 * AppSecret 只进不出（视图仅含 appId 与 secretConfigured 布尔标记）。
 * 路由前缀 /api/admin 由身份模块拦截器强制「登录 + ADMIN 角色」。
 */
@RestController
@RequestMapping("/api/admin/wechat-credential")
@RequiredArgsConstructor
public class AdminWechatCredentialController {

    private final WechatCredentialService wechatCredentialService;

    /** 查询当前凭据（AppSecret 永不下发，仅返回是否已配置）。 */
    @GetMapping
    public Result<WechatCredentialView> get() {
        return Result.success(wechatCredentialService.getAdminView());
    }

    /** 保存微信小程序登录凭据（AppID + AppSecret，密钥加密落库）。 */
    @PutMapping
    public Result<WechatCredentialView> save(@RequestBody SaveWechatCredentialRequest request) {
        return Result.success(wechatCredentialService.save(request.appId(), request.appSecret()));
    }

    /** 清空凭据：删除配置，微信登录通道随即关闭。 */
    @DeleteMapping
    public Result<Void> clear() {
        wechatCredentialService.clear();
        return Result.success();
    }
}