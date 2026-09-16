package com.xander.lab.billing.api;

import com.xander.lab.billing.application.BillingService;
import com.xander.lab.billing.application.BillingService.ModelPriceView;
import com.xander.lab.common.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理台模型定价维护：查看 / 保存 / 删除各模型 token 单价。
 * 路由前缀 /api/admin 由身份模块拦截器强制「登录 + ADMIN 角色」。
 */
@RestController
@RequestMapping("/api/admin/billing/model-prices")
@RequiredArgsConstructor
public class AdminModelPriceController {

    private final BillingService billingService;

    /** 全部模型定价，按模型名排序。 */
    @GetMapping
    public Result<List<ModelPriceView>> list() {
        return Result.success(billingService.listModelPrices());
    }

    /** 新增或更新模型定价（按 model 幂等 upsert），立即对后续计费生效。 */
    @PutMapping("/{model}")
    public Result<ModelPriceView> upsert(@PathVariable String model,
                                         @Valid @RequestBody SaveModelPriceRequest request) {
        // 路径变量与请求体不一致时以路径为准，避免两处模型名歧义。
        return Result.success(billingService.upsertModelPrice(model,
                request.inputMilliPer1kToken(), request.outputMilliPer1kToken(),
                request.cachedMilliPer1kToken(), request.cachedWriteMilliPer1kToken(),
                request.enabled(), request.remark()));
    }

    /** 删除模型定价；删除后该模型计费回退 application.yml 默认价。 */
    @DeleteMapping("/{model}")
    public Result<Void> delete(@PathVariable String model) {
        billingService.deleteModelPrice(model);
        return Result.success(null);
    }
}
