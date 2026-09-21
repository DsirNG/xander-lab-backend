package com.xander.lab.automation.host;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import com.xander.lab.common.security.SecurityPlaneConfiguration;

/** Entry point for the Wave 2 content-automation deployment boundary. */
@SpringBootApplication
@EnableScheduling
@ComponentScan(basePackages = {
        "com.xander.lab.automation",
        "com.xander.lab.publishing",
        "com.xander.lab.blogagent"
})
@EnableConfigurationProperties({ContentAutomationProperties.class, ContentPlanExecutionProperties.class})
@Import(SecurityPlaneConfiguration.class)
public class ContentAutomationApplication {

    public static void main(String[] args) {
        SpringApplication.run(ContentAutomationApplication.class, args);
    }
}
