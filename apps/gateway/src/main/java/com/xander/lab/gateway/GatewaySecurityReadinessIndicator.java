package com.xander.lab.gateway;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** 将 Gateway 安全平面启动闸门接入 Actuator readiness group。 */
@Component("gatewaySecurity")
public class GatewaySecurityReadinessIndicator implements HealthIndicator {

    private final GatewaySecurityPlaneReadiness readiness;

    public GatewaySecurityReadinessIndicator(GatewaySecurityPlaneReadiness readiness) {
        this.readiness = readiness;
    }

    @Override
    public Health health() {
        if (readiness.isReady()) {
            return Health.up()
                    .withDetail("state", readiness.state())
                    .withDetail("readyAtMillis", readiness.readyAtMillis())
                    .build();
        }
        Health.Builder health = Health.outOfService()
                .withDetail("state", readiness.state());
        if (readiness.failureReason() != null) {
            health.withDetail("failureReason", readiness.failureReason());
        }
        return health.build();
    }
}
