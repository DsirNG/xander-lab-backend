package com.xander.lab.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

class HealthControllerTest {

    @Test
    void reportsRedisAsUpWhenPingSucceeds() {
        RedisHealthProbe probe = mock(RedisHealthProbe.class);

        var response = new HealthController(probe).health();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP").containsEntry("redis", "UP");
        verify(probe).ping();
    }

    @Test
    void reportsRedisAsDownWhenPingFails() {
        RedisHealthProbe probe = mock(RedisHealthProbe.class);
        doThrow(new RuntimeException("Redis is unavailable")).when(probe).ping();

        var response = new HealthController(probe).health();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("status", "DOWN").containsEntry("redis", "DOWN");
        verify(probe).ping();
    }
}
