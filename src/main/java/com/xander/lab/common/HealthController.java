package com.xander.lab.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Exposes a lightweight liveness/readiness probe for the backend and its Redis dependency. */
@RestController
public class HealthController {

    private final RedisHealthProbe redisHealthProbe;

    public HealthController(RedisHealthProbe redisHealthProbe) {
        this.redisHealthProbe = redisHealthProbe;
    }

    /** Returns 200 only when the application can successfully ping Redis. */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", "UP");
        status.put("message", "DinQorAI Backend is running");
        status.put("timestamp", System.currentTimeMillis());
        try {
            redisHealthProbe.ping();
            status.put("redis", "UP");
            return ResponseEntity.ok(status);
        } catch (RuntimeException ex) {
            status.put("status", "DOWN");
            status.put("redis", "DOWN");
            status.put("message", "Redis dependency is unavailable");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(status);
        }
    }
}
