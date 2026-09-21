package com.xander.lab.automation.infrastructure;

import com.xander.lab.common.security.SecurityPlaneReadiness;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** 将 content-automation 安全平面 Bootstrap 状态接入 Actuator readiness group。 */
@Component("securityPlane")
public class ContentSecurityPlaneHealthIndicator implements HealthIndicator {

    private final SecurityPlaneReadiness readiness;

    public ContentSecurityPlaneHealthIndicator(SecurityPlaneReadiness readiness) {
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
        Health.Builder health = Health.down().withDetail("state", readiness.state());
        if (readiness.failureReason() != null) {
            health.withDetail("failureReason", readiness.failureReason());
        }
        return health.build();
    }
}
