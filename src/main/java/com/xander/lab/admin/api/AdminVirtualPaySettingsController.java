package com.xander.lab.admin.api;

import com.xander.lab.common.Result;
import com.xander.lab.virtualpay.api.VirtualPaySettingsView;
import com.xander.lab.virtualpay.application.VirtualPaySettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 管理员维护个人主体小程序虚拟支付配置。 */
@RestController
@RequestMapping("/api/admin/virtual-pay-settings")
@RequiredArgsConstructor
public class AdminVirtualPaySettingsController {
    private final VirtualPaySettingsService service;

    @GetMapping
    public Result<VirtualPaySettingsView> get() { return Result.success(service.adminView()); }

    @PutMapping
    public Result<VirtualPaySettingsView> save(@RequestBody SaveVirtualPaySettingsRequest request) {
        return Result.success(service.save(request));
    }
}
