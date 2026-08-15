package com.xander.lab.admin.api;

import com.xander.lab.admin.application.ModelProviderService;
import com.xander.lab.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理台模型供应商 CRUD。API 密钥只进不出（视图仅含 apiKeyConfigured 布尔标记）。
 * 路由前缀 /api/admin 由身份模块拦截器强制「登录 + ADMIN 角色」。
 */
@RestController
@RequestMapping("/api/admin/model-providers")
@RequiredArgsConstructor
public class AdminModelProviderController {

    private final ModelProviderService providerService;

    @GetMapping
    public Result<List<ModelProviderView>> list() {
        return Result.success(providerService.list());
    }

    @PostMapping
    public Result<ModelProviderView> create(@RequestBody CreateProviderRequest request) {
        return Result.success(providerService.create(request));
    }

    @PutMapping("/{id}")
    public Result<ModelProviderView> update(@PathVariable Long id,
                                            @RequestBody UpdateProviderRequest request) {
        return Result.success(providerService.update(id, request));
    }

    /** 启用/停用供应商；停用的供应商不会被任何功能选中。 */
    @PatchMapping("/{id}/enabled")
    public Result<ModelProviderView> setEnabled(@PathVariable Long id,
                                                @RequestBody SetProviderEnabledRequest request) {
        providerService.setEnabled(id, request.enabled());
        return Result.success(providerService.list().stream()
                .filter(view -> id.equals(view.id()))
                .findFirst().orElseThrow(() -> new IllegalStateException("供应商不存在")));
    }

    /** 删除供应商；仍被功能配置引用时返回 409。 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        providerService.delete(id);
        return Result.success();
    }
}