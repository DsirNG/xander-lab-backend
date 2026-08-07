package com.xander.lab.publishing.infrastructure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Runtime configuration for the CSDN publisher. Cookie is supplied by environment only. */
@Data
@Component
@ConfigurationProperties(prefix = "publishing.csdn")
public class CsdnProperties {
    private boolean enabled;
    private String baseUrl = "https://bizapi.csdn.net";
    private String apiKey = "203803574";
    /** Captured CSDN web-console signing pair; not a user credential. */
    private String signature = "LR91sXgft3iYsI+K0/cRgOCK5LzLHJbUSgKRPrUEaR8=";
    private String nonce = "6fc69002-35f0-4d7c-8fde-67c9cd36ba4b";
    private String seleniumUrl = "http://selenium:4444/wd/hub";
    private String credentialEncryptionKey;
    private int authorizationTimeoutSeconds = 180;
}
