package com.xander.lab.identity.api;

import com.xander.lab.identity.application.SecurityStateSyncProperties;
import com.xander.lab.payment.api.BillingPort;
import com.xander.lab.payment.api.TokenUsage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Agent 专用的内网计费契约 API；默认关闭，不暴露账本查询与管理接口。 */
@RestController
@RequestMapping("/internal/agent/billing")
@ConditionalOnProperty(prefix = "security.state-sync", name = "internal-api-enabled",
        havingValue = "true")
public class AgentBillingController {
    private static final String INTERNAL_TOKEN_HEADER = "X-DinQor-Internal-Token";

    private final BillingPort billingPort;
    private final SecurityStateSyncProperties properties;

    public AgentBillingController(BillingPort billingPort,
                                  SecurityStateSyncProperties properties) {
        this.billingPort = billingPort;
        this.properties = properties;
        if (properties.getInternalToken() == null || properties.getInternalToken().isBlank()) {
            throw new IllegalStateException(
                    "security.state-sync.internal-token 在启用内网同步 API 时不能为空");
        }
    }

    @PostMapping("/charge-tokens")
    public void chargeTokens(@RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
                             @RequestBody ChargeTokensRequest request) {
        authorize(token);
        billingPort.chargeTokens(request.userId(), request.bizType(), request.bizKey(), request.bizRef(),
                new TokenUsage(request.inputTokens(), request.cachedTokens(), request.outputTokens()),
                request.model(), request.remark());
    }

    @PostMapping("/charge-flat")
    public void chargeFlat(@RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
                           @RequestBody ChargeFlatRequest request) {
        authorize(token);
        billingPort.chargeFlat(request.userId(), request.bizType(), request.bizKey(), request.bizRef(),
                request.points(), request.remark());
    }

    @PostMapping("/flat-points")
    public PointsResponse flatPoints(@RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
                                     @RequestBody BizTypeRequest request) {
        authorize(token);
        return new PointsResponse(billingPort.flatPoints(request.bizType()));
    }

    @PostMapping("/ensure-available")
    public void ensureAvailable(@RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
                                @RequestBody UserRequest request) {
        authorize(token);
        billingPort.ensureAvailable(request.userId());
    }

    @PostMapping("/balance")
    public BalanceResponse balance(@RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
                                   @RequestBody UserRequest request) {
        authorize(token);
        return new BalanceResponse(billingPort.balance(request.userId()));
    }

    @PostMapping("/grant-register")
    public void grantRegister(@RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
                              @RequestBody GrantRegisterRequest request) {
        authorize(token);
        billingPort.grantRegister(request.userId(), request.bizKey());
    }

    @PostMapping("/transfer-balance")
    public void transferBalance(@RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
                                @RequestBody TransferBalanceRequest request) {
        authorize(token);
        billingPort.transferBalance(request.fromUserId(), request.toUserId(), request.bizKey(), request.remark());
    }

    private void authorize(String token) {
        if (token == null || !MessageDigest.isEqual(
                properties.getInternalToken().getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not Found");
        }
    }

    public record ChargeTokensRequest(Long userId, String bizType, String bizKey, String bizRef,
                                      long inputTokens, long cachedTokens, long outputTokens,
                                      String model, String remark) {
    }

    public record ChargeFlatRequest(Long userId, String bizType, String bizKey, String bizRef,
                                    long points, String remark) {
    }

    public record BizTypeRequest(String bizType) {
    }

    public record UserRequest(Long userId) {
    }

    public record GrantRegisterRequest(Long userId, String bizKey) {
    }

    public record TransferBalanceRequest(Long fromUserId, Long toUserId, String bizKey, String remark) {
    }

    public record PointsResponse(long points) {
    }

    public record BalanceResponse(long balance) {
    }
}
