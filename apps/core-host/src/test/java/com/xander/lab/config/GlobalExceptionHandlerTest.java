package com.xander.lab.config;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void mapsRedisFailuresToServiceUnavailable() {
        RedisSystemException exception = new RedisSystemException(
                "Error in execution", new IllegalStateException("Redis command failed"));

        ResponseEntity<?> response = handler.handleRedisException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).hasFieldOrPropertyWithValue("code", 503);
    }

    @Test
    void mapsMissingStaticResourcesToNotFound() {
        NoResourceFoundException exception = new NoResourceFoundException(HttpMethod.GET, "favicon.ico");

        var response = handler.handleNoResourceFound(exception);

        assertThat(response.getCode()).isEqualTo(404);
        assertThat(response.getMessage()).isEqualTo("资源不存在");
    }
}
