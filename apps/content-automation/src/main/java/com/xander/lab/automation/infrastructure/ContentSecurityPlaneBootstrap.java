package com.xander.lab.automation.infrastructure;

import com.xander.lab.automation.host.ContentSecurityStateProperties;
import com.xander.lab.common.security.SecurityPlaneReadiness;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 对账关闭时显式放行 content-automation 的本地安全平面，避免伪造未完成同步。 */
@Component
public class ContentSecurityPlaneBootstrap implements ApplicationRunner {

    private final ContentSecurityStateProperties properties;
    private final SecurityPlaneReadiness readiness;

    public ContentSecurityPlaneBootstrap(ContentSecurityStateProperties properties,
                                         SecurityPlaneReadiness readiness) {
        this.properties = properties;
        this.readiness = readiness;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isReconcileEnabled()) {
            readiness.markReady(System.currentTimeMillis());
        }
    }
}
