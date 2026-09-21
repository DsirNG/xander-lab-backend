package com.xander.lab.agent.infrastructure;

import com.xander.lab.agent.host.AgentSecurityStateProperties;
import com.xander.lab.common.security.SecurityPlaneReadiness;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 对账关闭时显式放行 Agent 安全平面；开启对账时由 reconciler 完成 Bootstrap。 */
@Component
public class AgentSecurityPlaneBootstrap implements ApplicationRunner {

    private final AgentSecurityStateProperties properties;
    private final SecurityPlaneReadiness readiness;

    public AgentSecurityPlaneBootstrap(AgentSecurityStateProperties properties,
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
