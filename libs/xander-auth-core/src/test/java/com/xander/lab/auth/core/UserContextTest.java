package com.xander.lab.auth.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserContextTest {
    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    void storesAndClearsRequestIdentity() {
        UserContext.setUserId(42L);
        UserContext.setClientIp("203.0.113.8");

        assertThat(UserContext.getUserId()).isEqualTo(42L);
        assertThat(UserContext.getClientIp()).isEqualTo("203.0.113.8");

        UserContext.clear();
        assertThat(UserContext.getUserId()).isNull();
        assertThat(UserContext.getClientIp()).isNull();
    }
}
