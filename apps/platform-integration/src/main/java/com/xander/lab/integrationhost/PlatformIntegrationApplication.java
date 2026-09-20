package com.xander.lab.integration.host;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Wave 2 platform-integration deployment unit.
 *
 * <p>The compatibility proxy is intentionally isolated from core-host packages. This keeps
 * the deployment boundary real while the implementation moves behind the already frozen HTTP
 * contract.</p>
 */
@SpringBootApplication
public class PlatformIntegrationApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlatformIntegrationApplication.class, args);
    }
}
