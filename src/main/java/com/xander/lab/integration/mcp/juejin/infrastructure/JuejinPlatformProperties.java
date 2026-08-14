package com.xander.lab.integration.mcp.juejin.infrastructure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Runtime configuration for the Juejin publisher. Credentials are supplied per user, never globally. */
@Data
@Component
@ConfigurationProperties(prefix = "mcp.juejin.platform")
public class JuejinPlatformProperties {
    private boolean enabled;
    private String baseUrl = "https://api.juejin.cn";
    /** 掘金 web 端固定应用 ID，参与所有 content_api 请求的 URL 查询参数。 */
    private String defaultAid = "2608";
    /** 加密凭据用的 Base64 32 字节密钥，来自 JUEJIN_CREDENTIAL_ENCRYPTION_KEY 环境变量。 */
    private String credentialEncryptionKey;
    /** 建立连接超时（毫秒）。 */
    private int connectTimeoutMillis = 10_000;
    /** 单次请求读超时（毫秒），发布链路包含草稿创建与发布两次调用。 */
    private int readTimeoutMillis = 30_000;
    /** 掘金扫码授权用的 Selenium Hub 地址。 */
    private String seleniumUrl = "http://selenium:4444/wd/hub";
    /** 扫码授权会话有效期（秒），过期后需重新发起。 */
    private int authorizationTimeoutSeconds = 60;
    /** 后台清理过期授权会话的扫描间隔（毫秒）。 */
    private long cleanupIntervalMs = 15_000;
    /** 分类/标签目录的新鲜度窗口（小时），过期或为空时在发布链路触发全量同步。 */
    private int catalogFreshnessHours = 24;
    /** 掘金登录页二维码选择器（无头浏览器在登录框内找到的图片节点）。 */
    private String qrImageSelector = ".qrcode-img-wrap img.qrcode-img";
}