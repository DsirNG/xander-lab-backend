package com.xander.lab.integration.wechat.api;

import com.xander.lab.common.Result;
import com.xander.lab.identity.security.UserContext;
import com.xander.lab.integration.wechat.application.WechatOfficialAccountService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** User API for configuring one directly connected official account. */
@RestController
@RequestMapping("/api/publishing/wechat/authorization")
@RequiredArgsConstructor
public class WechatOfficialAccountController {
    private final WechatOfficialAccountService service;

    @GetMapping("/status")
    public Result<WechatOfficialAccountService.AccountView> status() {
        return Result.success(service.status(requireUserId()));
    }

    @PostMapping
    public Result<WechatOfficialAccountService.AccountView> save(
            @Valid @RequestBody SaveRequest request) {
        return Result.success(service.save(requireUserId(), request.appId(), request.appSecret(), request.accountName()));
    }

    @PostMapping("/test")
    public Result<WechatOfficialAccountService.AccountView> test() {
        return Result.success(service.testConnection(requireUserId()));
    }

    @DeleteMapping
    public Result<Void> disconnect() {
        service.disconnect(requireUserId());
        return Result.success(null);
    }

    private Long requireUserId() {
        Long userId = UserContext.getUserId();
        if (userId == null) throw new IllegalStateException("Authentication is required");
        return userId;
    }

    public record SaveRequest(
            @NotBlank(message = "AppID 不能为空") String appId,
            @NotBlank(message = "AppSecret 不能为空") String appSecret,
            String accountName) {
    }
}
