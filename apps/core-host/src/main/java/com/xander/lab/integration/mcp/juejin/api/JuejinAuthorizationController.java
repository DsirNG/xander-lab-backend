package com.xander.lab.integration.mcp.juejin.api;

import com.xander.lab.common.Result;
import com.xander.lab.auth.core.UserContext;
import com.xander.lab.integration.mcp.juejin.application.JuejinAuthorizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** C-end API for connecting one user's Juejin account by QR code. */
@RestController
@RequestMapping("/api/publishing/juejin/authorization")
@RequiredArgsConstructor
public class JuejinAuthorizationController {
    private final JuejinAuthorizationService service;

    @PostMapping("/start")
    public Result<JuejinAuthorizationService.AuthorizationView> start() {
        return Result.success(service.start(requireUserId()));
    }

    @GetMapping("/status")
    public Result<JuejinAuthorizationService.AuthorizationView> status() {
        return Result.success(service.status(requireUserId()));
    }

    @DeleteMapping
    public Result<Void> disconnect() {
        service.disconnect(requireUserId());
        return Result.success(null);
    }

    @PostMapping("/cancel")
    public Result<Void> cancel() {
        service.cancel(requireUserId());
        return Result.success(null);
    }

    private Long requireUserId() {
        Long userId = UserContext.getUserId();
        if (userId == null) throw new IllegalStateException("Authentication is required");
        return userId;
    }
}
