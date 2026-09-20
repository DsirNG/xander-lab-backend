package com.xander.lab.automation.host;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;

/** Entry point for the Wave 2 content-automation deployment boundary. */
@SpringBootApplication
@ComponentScan(basePackages = {
        "com.xander.lab.automation",
        "com.xander.lab.publishing"
})
@EnableConfigurationProperties(ContentAutomationProperties.class)
public class ContentAutomationApplication {

    public static void main(String[] args) {
        SpringApplication.run(ContentAutomationApplication.class, args);
    }
}
