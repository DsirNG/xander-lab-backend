package com.xander.lab.integration.host;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Wave 2 platform-integration deployment unit.
 *
 * <p>The compatibility proxy is intentionally isolated from core-host packages. Platform-owned
 * vertical slices can therefore replace the fallback one route at a time without changing the
 * frozen HTTP contract.</p>
 */
@SpringBootApplication
public class PlatformIntegrationApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlatformIntegrationApplication.class, args);
    }
}
