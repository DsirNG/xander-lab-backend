package com.xander.lab.integration.mcp.csdn.api;

import com.xander.lab.common.Result;
import com.xander.lab.auth.core.UserContext;
import com.xander.lab.integration.mcp.csdn.application.CsdnAuthorizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** C-end API for connecting one user's CSDN account by QR code. */
@RestController
@RequestMapping("/api/publishing/csdn/authorization")
@ConditionalOnProperty(prefix = "core.legacy", name = "platform-public-enabled",
        havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class CsdnAuthorizationController {
    private final CsdnAuthorizationService service;

    @PostMapping("/start")
    public Result<CsdnAuthorizationService.AuthorizationView> start() {
        return Result.success(service.start(requireUserId()));
    }

    @GetMapping("/status")
    public Result<CsdnAuthorizationService.AuthorizationView> status() {
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
