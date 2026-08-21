package com.xander.lab.billing.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 积分计费配置。
 *
 * <p>账本单位为「毫分」（1 分 = 1000 毫分，1 元 = 100_000 毫分），余额与流水全链路毫分，
 * token 计费精确到毫分不再舍入到整分。token 单价以「毫分 / 每 1K token」为单位：
 * 一次调用毫分 = round(input*inputPrice + cached*cachedPrice + output*outputPrice)，
 * 至少 1 毫分。一口价（配图 / 邮件 / CSDN）直接按 biz_type 配置毫分值。</p>
 *
 * <p>支持按模型配置不同价格：models.{model}.price 优先级高于默认 price（数据库
 * billing_model_price 又优先于本配置），未配置的模型使用默认价格。例如：</p>
 * <pre>
 * billing.models.gpt-5.6-terra.price.input-milli-per-1k-token=200
 * </pre>
 *
 * <pre>
 * billing.enabled=true
 * billing.grant-register=2000000   # 2000 分 = 2,000,000 毫分
 * billing.price.input-milli-per-1k-token=500
 * billing.flat.blog-agent-illustration=50000
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "billing")
public class BillingProperties {

    /** 积分开关；关闭后所有计费与余额预检短路，行为回到现状。 */
    private boolean enabled = true;

    /** 注册赠送积分（毫分）：2000 分 */
    private int grantRegister = 2_000_000;

    /** 余额预检阈值（毫分）：余额低于该值时拒绝发起新消耗，默认 1 分 */
    private int preCheckMinBalance = 1_000;

    /** 一口价：BillingBiz 业务名 -> 毫分 */
    private Map<String, Integer> flat = new LinkedHashMap<>(Map.of(
            "blog-agent-illustration", 50_000,
            "email-reminder", 5_000,
            "csdn-publish", 10_000,
            "juejin-publish", 10_000
    ));

    /** 默认 token 单价（未配置模型专属价格时使用） */
    private Price price = new Price();

    /** 按模型配置的专属价格：model -> ModelPrice */
    private Map<String, ModelPrice> models = new LinkedHashMap<>();

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

    /** 模型专属价格配置，包含可选的 cache-write 单价。 */
    public static class ModelPrice {
        private Price price = new Price();
        /** 缓存写入 token 单价：毫分 / 每 1K token */
        private long cachedWriteMilliPer1kToken = 0L;

        public Price getPrice() { return price; }
        public void setPrice(Price price) { this.price = price; }
        public long getCachedWriteMilliPer1kToken() { return cachedWriteMilliPer1kToken; }
        public void setCachedWriteMilliPer1kToken(long value) { this.cachedWriteMilliPer1kToken = value; }
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
    public Map<String, ModelPrice> getModels() { return models; }
    public void setModels(Map<String, ModelPrice> models) { this.models = models; }

    /** 一口价，缺省返回 0（不扣费）。 */
    public long flatPoints(String key) {
        return flat.getOrDefault(key, 0).longValue();
    }

    /**
     * 获取指定模型的 token 单价。
     * 优先返回模型专属价格，未配置则回退到默认价格。
     *
     * @param model 模型名称，null 或未配置时返回默认价格
     * @return 该模型对应的 Price 配置
     */
    public Price priceForModel(String model) {
        if (model != null && models.containsKey(model)) {
            return models.get(model).getPrice();
        }
        return price;
    }
}