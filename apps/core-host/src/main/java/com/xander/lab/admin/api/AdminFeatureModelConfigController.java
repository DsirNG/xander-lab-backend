package com.xander.lab.admin.api;

import com.xander.lab.admin.application.FeatureModelConfigService;
import com.xander.lab.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理台功能模型配置：为每个功能设置主模型与兜底模型。
 * 路由前缀 /api/admin 由身份模块拦截器强制「登录 + ADMIN 角色」。
 */
@RestController
@RequestMapping("/api/admin/feature-model-configs")
@RequiredArgsConstructor
public class AdminFeatureModelConfigController {

    private final FeatureModelConfigService configService;

    /** 返回全部功能（含未配置的默认空视图），顺序固定。 */
    @GetMapping
    public Result<List<FeatureConfigView>> list() {
        return Result.success(configService.listConfigs());
    }

    @PutMapping("/{featureKey}")
    public Result<FeatureConfigView> update(@PathVariable String featureKey,
                                            @RequestBody UpdateFeatureConfigRequest request) {
        return Result.success(configService.update(featureKey, request));
    }
}