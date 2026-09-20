package com.xander.lab.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Gateway 安全事件快路径配置；默认关闭，避免本地开发强制依赖 Redis。 */
@Component
@ConfigurationProperties(prefix = "gateway.security-state")
public class GatewaySecurityStateProperties {

    private boolean pubsubEnabled = false;
    private String pubsubChannel = "dinqor:security-events";
    private boolean reconcileEnabled = false;
    private String coreHostBaseUrl = "http://localhost:30002";
    private String internalToken = "";
    private long reconcileIntervalMs = 1500L;
    private long fullReconcileIntervalMs = 300000L;
    private int reconcileBatchSize = 500;
    private long requestTimeoutMs = 2000L;

    public boolean isPubsubEnabled() {
        return pubsubEnabled;
    }

    public void setPubsubEnabled(boolean pubsubEnabled) {
        this.pubsubEnabled = pubsubEnabled;
    }

    public String getPubsubChannel() {
        return pubsubChannel;
    }

    public void setPubsubChannel(String pubsubChannel) {
        this.pubsubChannel = pubsubChannel;
    }

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
