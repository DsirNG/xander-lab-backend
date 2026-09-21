package com.xander.lab.automation.host;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** content-automation 本实例安全状态同步配置；默认关闭，兼容未配置内网令牌的本地环境。 */
@Component
@ConfigurationProperties(prefix = "content-automation.security-state")
public class ContentSecurityStateProperties {

    private boolean reconcileEnabled;
    private String coreHostBaseUrl = "http://localhost:30002";
    private String internalToken = "";
    private long reconcileIntervalMs = 1500L;
    private long fullReconcileIntervalMs = 300000L;
    private int reconcileBatchSize = 500;
    private long requestTimeoutMs = 2000L;

    public boolean isReconcileEnabled() {
        return reconcileEnabled;
    }

    public void setReconcileEnabled(boolean reconcileEnabled) {
        this.reconcileEnabled = reconcileEnabled;
    }

    public String getCoreHostBaseUrl() {
        return coreHostBaseUrl;
    }

    public void setCoreHostBaseUrl(String coreHostBaseUrl) {
        this.coreHostBaseUrl = coreHostBaseUrl;
    }

    public String getInternalToken() {
        return internalToken;
    }

    public void setInternalToken(String internalToken) {
        this.internalToken = internalToken;
    }

    public long getReconcileIntervalMs() {
        return reconcileIntervalMs;
    }

    public void setReconcileIntervalMs(long reconcileIntervalMs) {
        this.reconcileIntervalMs = reconcileIntervalMs;
    }

    public long getFullReconcileIntervalMs() {
        return fullReconcileIntervalMs;
    }

    public void setFullReconcileIntervalMs(long fullReconcileIntervalMs) {
        this.fullReconcileIntervalMs = fullReconcileIntervalMs;
    }

    public int getReconcileBatchSize() {
        return reconcileBatchSize;
    }

    public void setReconcileBatchSize(int reconcileBatchSize) {
        this.reconcileBatchSize = reconcileBatchSize;
    }

    public long getRequestTimeoutMs() {
        return requestTimeoutMs;
    }

    public void setRequestTimeoutMs(long requestTimeoutMs) {
        this.requestTimeoutMs = requestTimeoutMs;
    }
}
