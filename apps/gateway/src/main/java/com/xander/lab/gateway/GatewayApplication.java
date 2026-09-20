package com.xander.lab.gateway;

import com.xander.lab.auth.core.JwtUtil;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/** WebFlux-only entry point for the DinQorAI edge gateway. */
@SpringBootApplication
@EnableScheduling
@Import(JwtUtil.class)
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
