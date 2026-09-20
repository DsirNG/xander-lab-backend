package com.xander.lab.agent.infrastructure;

import com.xander.lab.payment.api.BillingPort;
import com.xander.lab.payment.api.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/** 通过 core-host 内网契约 API 调用计费服务。 */
@Component
@ConditionalOnProperty(prefix = "agent.core-client", name = "enabled", havingValue = "true")
public class AgentBillingHttpAdapter implements BillingPort {
    private static final Logger log = LoggerFactory.getLogger(AgentBillingHttpAdapter.class);
    private static final String INTERNAL_TOKEN_HEADER = "X-DinQor-Internal-Token";

    private final RestClient restClient;
    private final String internalToken;

    @Autowired
    public AgentBillingHttpAdapter(RestClient.Builder builder,
                                   AgentCoreClientProperties properties) {
        if (properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()) {
            throw new IllegalStateException("agent.core-client.base-url 在启用 core-client 时不能为空");
        }
        if (properties.getInternalToken() == null || properties.getInternalToken().isBlank()) {
            throw new IllegalStateException(
                    "agent.core-client.internal-token 在启用 core-client 时不能为空");
        }
        String baseUrl = properties.getBaseUrl().endsWith("/")
                ? properties.getBaseUrl().substring(0, properties.getBaseUrl().length() - 1)
                : properties.getBaseUrl();
        this.restClient = builder.baseUrl(baseUrl).build();
        this.internalToken = properties.getInternalToken();
    }

    @Override
    public void chargeTokens(Long userId, String bizType, String bizKey, String bizRef,
                             TokenUsage usage, String model, String remark) {
        TokenUsage safeUsage = usage == null ? TokenUsage.NONE : usage;
        post("/internal/agent/billing/charge-tokens",
                new ChargeTokensRequest(userId, bizType, bizKey, bizRef,
                        safeUsage.inputTokens(), safeUsage.cachedTokens(), safeUsage.outputTokens(),
                        model, remark), Void.class);
    }

    @Override
    public void chargeFlat(Long userId, String bizType, String bizKey, String bizRef,
                           long points, String remark) {
        post("/internal/agent/billing/charge-flat",
                new ChargeFlatRequest(userId, bizType, bizKey, bizRef, points, remark), Void.class);
    }

    @Override
    public long flatPoints(String bizType) {
        PointsResponse response = post("/internal/agent/billing/flat-points",
                new BizTypeRequest(bizType), PointsResponse.class);
        return response == null ? 0L : response.points();
    }

    @Override
    public void ensureAvailable(Long userId) {
        try {
            post("/internal/agent/billing/ensure-available", new UserRequest(userId), Void.class);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == HttpStatus.PAYMENT_REQUIRED.value()) {
                throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "积分不足，请先获取积分");
            }
            throw unavailable(exception);
        }
    }

    @Override
    public long balance(Long userId) {
        BalanceResponse response = post("/internal/agent/billing/balance",
                new UserRequest(userId), BalanceResponse.class);
        return response == null ? 0L : response.balance();
    }

    @Override
    public void grantRegister(Long userId, String bizKey) {
        post("/internal/agent/billing/grant-register",
                new GrantRegisterRequest(userId, bizKey), Void.class);
    }

    @Override
    public void transferBalance(Long fromUserId, Long toUserId, String bizKey, String remark) {
        post("/internal/agent/billing/transfer-balance",
                new TransferBalanceRequest(fromUserId, toUserId, bizKey, remark), Void.class);
    }

    private <T> T post(String path, Object body, Class<T> responseType) {
        try {
            return restClient.post()
                    .uri(path)
                    .header(INTERNAL_TOKEN_HEADER, internalToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(responseType);
        } catch (RestClientResponseException exception) {
            throw unavailable(exception);
        } catch (RestClientException exception) {
            throw unavailable(exception);
        }
    }

    private IllegalStateException unavailable(Exception exception) {
        log.warn("core-host billing boundary unavailable; rejecting billing operation");
        return new IllegalStateException("core-host billing boundary unavailable", exception);
    }

    private record ChargeTokensRequest(Long userId, String bizType, String bizKey, String bizRef,
                                       long inputTokens, long cachedTokens, long outputTokens,
                                       String model, String remark) {
    }

    private record ChargeFlatRequest(Long userId, String bizType, String bizKey, String bizRef,
                                     long points, String remark) {
    }

    private record BizTypeRequest(String bizType) {
    }

    private record UserRequest(Long userId) {
    }

    private record GrantRegisterRequest(Long userId, String bizKey) {
    }

    private record TransferBalanceRequest(Long fromUserId, Long toUserId, String bizKey, String remark) {
    }

    private record PointsResponse(long points) {
    }

    private record BalanceResponse(long balance) {
    }
}
