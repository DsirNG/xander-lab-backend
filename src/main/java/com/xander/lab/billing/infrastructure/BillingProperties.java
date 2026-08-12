package com.xander.lab.billing.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 积分计费配置。
 *
 * <p>token 单价以「毫分 / 每 1K token」为单位：一次调用积分 =
 * round((input*inputPrice + cached*cachedPrice + output*outputPrice) / 1_000),
 * 至少 1 分。一口价（配图 / 邮件 / CSDN）直接按 biz_type 配置积分值。</p>
 *
 * <pre>
 * billing.enabled=true
 * billing.grant-register=2000
 * billing.price.input-milli-per-1k-token=500
 * billing.price.output-milli-per-1k-token=2000
 * billing.price.cached-milli-per-1k-token=50
 * billing.flat.blog-agent-illustration=50
 * billing.flat.email-reminder=5
 * billing.flat.csdn-publish=10
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "billing")
public class BillingProperties {

    /** 积分开关；关闭后所有计费与余额预检短路，行为回到现状。 */
    private boolean enabled = true;

    /** 注册赠送积分 */
    private int grantRegister = 2000;

    /** 余额预检阈值：余额低于该值时拒绝发起新消耗 */
    private int preCheckMinBalance = 1;

    /** 一口价：BillingBiz 业务名 -> 积分 */
    private Map<String, Integer> flat = new LinkedHashMap<>(Map.of(
            "blog-agent-illustration", 50,
            "email-reminder", 5,
            "csdn-publish", 10
    ));

    private Price price = new Price();

    public static class Price {
        /** 输入 token 单价：毫分 / 每 1K token */
        private long inputMilliPer1kToken = 500L;
        /** 缓存命中 token 单价：毫分 / 每 1K token */
        private long cachedMilliPer1kToken = 50L;
        /** 输出 token 单价：毫分 / 每 1K token */
        private long outputMilliPer1kToken = 2000L;

        public long getInputMilliPer1kToken() { return inputMilliPer1kToken; }
        public void setInputMilliPer1kToken(long value) { this.inputMilliPer1kToken = value; }
        public long getCachedMilliPer1kToken() { return cachedMilliPer1kToken; }
        public void setCachedMilliPer1kToken(long value) { this.cachedMilliPer1kToken = value; }
        public long getOutputMilliPer1kToken() { return outputMilliPer1kToken; }
        public void setOutputMilliPer1kToken(long value) { this.outputMilliPer1kToken = value; }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getGrantRegister() { return grantRegister; }
    public void setGrantRegister(int grantRegister) { this.grantRegister = grantRegister; }
    public int getPreCheckMinBalance() { return preCheckMinBalance; }
    public void setPreCheckMinBalance(int preCheckMinBalance) { this.preCheckMinBalance = preCheckMinBalance; }
    public Map<String, Integer> getFlat() { return flat; }
    public void setFlat(Map<String, Integer> flat) { this.flat = flat; }
    public Price getPrice() { return price; }
    public void setPrice(Price price) { this.price = price; }

    /** 一口价，缺省返回 0（不扣费）。 */
    public long flatPoints(String key) {
        return flat.getOrDefault(key, 0).longValue();
    }
}