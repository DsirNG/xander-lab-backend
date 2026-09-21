package com.xander.lab.integration.internal.api;

import com.xander.lab.payment.api.BillingPort;
import com.xander.lab.integration.internal.application.IntegrationInternalApiProperties;
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

/** Narrow billing boundary used by the content-owned reminder scheduler. */
@RestController
@RequestMapping("/internal/automation/billing")
@ConditionalOnProperty(prefix = "integration.internal", name = "api-enabled", havingValue = "true")
public class InternalAutomationBillingController {

    private static final String INTERNAL_TOKEN_HEADER = "X-DinQor-Internal-Token";

    private final BillingPort billingPort;
    private final IntegrationInternalApiProperties properties;

    public InternalAutomationBillingController(BillingPort billingPort,
                                                IntegrationInternalApiProperties properties) {
        this.billingPort = billingPort;
        this.properties = properties;
        if (properties.getInternalToken() == null || properties.getInternalToken().isBlank()) {
            throw new IllegalStateException(
                    "integration.internal.token 在启用自动化计费内网 API 时不能为空");
        }
    }

    @PostMapping("/flat-points")
    public PointsResponse flatPoints(@RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
                                     @RequestBody BizTypeRequest request) {
        authorize(token);
        if (request == null || request.bizType() == null || request.bizType().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "计费业务类型不能为空");
        }
        return new PointsResponse(billingPort.flatPoints(request.bizType()));
    }

    @PostMapping("/charge-flat")
    public void chargeFlat(@RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
                           @RequestBody ChargeFlatRequest request) {
        authorize(token);
        if (request == null || request.userId() == null || request.userId() <= 0
                || request.bizType() == null || request.bizType().isBlank()
                || request.bizKey() == null || request.bizKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "计费请求参数非法");
        }
        billingPort.chargeFlat(request.userId(), request.bizType(), request.bizKey(),
                request.bizRef(), request.points(), request.remark());
    }

    private void authorize(String token) {
        if (token == null || !MessageDigest.isEqual(
                properties.getInternalToken().getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not Found");
        }
    }

    public record BizTypeRequest(String bizType) {
    }

    public record ChargeFlatRequest(Long userId, String bizType, String bizKey, String bizRef,
                                    long points, String remark) {
    }

    public record PointsResponse(long points) {
    }
}
