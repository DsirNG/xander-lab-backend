package com.xander.lab.billing.api;

import com.xander.lab.billing.application.BillingService;
import com.xander.lab.common.Result;
import com.xander.lab.identity.security.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 积分账户余额、流水与计价目录查询。所有读操作仅限当前登录用户。 */
@RestController
@RequestMapping("/api/points")
@RequiredArgsConstructor
public class PointsController {

    private final BillingService billingService;

    @GetMapping
    public Result<Map<String, Object>> overview() {
        return Result.success(billingService.overview(UserContext.getUserId()));
    }

    @GetMapping("/ledger")
    public Result<BillingService.PointsLedgerPage> ledger(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(billingService.pageLedger(UserContext.getUserId(), page, size));
    }

    @GetMapping("/prices")
    public Result<Map<String, Object>> prices() {
        return Result.success(billingService.priceList());
    }
}