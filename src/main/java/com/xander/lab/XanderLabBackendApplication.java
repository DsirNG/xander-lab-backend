package com.xander.lab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class XanderLabBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(XanderLabBackendApplication.class, args);
    }

}
