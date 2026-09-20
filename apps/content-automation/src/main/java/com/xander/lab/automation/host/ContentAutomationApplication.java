package com.xander.lab.automation.host;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** Entry point for the Wave 2 content-automation deployment boundary. */
@SpringBootApplication
@EnableConfigurationProperties(ContentAutomationProperties.class)
public class ContentAutomationApplication {

    public static void main(String[] args) {
        SpringApplication.run(ContentAutomationApplication.class, args);
    }
}
