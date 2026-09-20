package com.xander.lab.gateway;

import org.springframework.stereotype.Component;

/** Gateway 安全状态同步的启动闸门；不开启对账时保持兼容的直接就绪语义。 */
@Component
public class GatewaySecurityPlaneReadiness {

    private final GatewaySecurityStateProperties properties;
    private volatile boolean ready;
    private volatile String failureReason;
    private volatile long readyAtMillis;

    public GatewaySecurityPlaneReadiness(GatewaySecurityStateProperties properties) {
        this.properties = properties;
        this.ready = !properties.isReconcileEnabled();
    }

    public boolean isReady() {
        return !properties.isReconcileEnabled() || ready;
    }

    public void markReady(long epochMillis) {
        readyAtMillis = epochMillis;
        failureReason = null;
        ready = true;
    }

    public void markFailed(String reason) {
        ready = false;
        failureReason = reason;
    }

    public String state() {
        return isReady() ? "READY" : "NOT_READY";
    }

    public long readyAtMillis() {
        return readyAtMillis;
    }

    public String failureReason() {
        return failureReason;
    }
}
