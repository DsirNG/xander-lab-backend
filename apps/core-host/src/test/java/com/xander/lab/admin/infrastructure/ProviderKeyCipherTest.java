package com.xander.lab.admin.infrastructure;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.GeneralSecurityException;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderKeyCipherTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private ModelGatewayProperties properties;
    private ProviderKeyCipher cipher;

    @BeforeEach
    void setUp() {
        properties = new ModelGatewayProperties();
        properties.setEnabled(true);
        properties.setEncryptionKey(KEY);
        cipher = new ProviderKeyCipher(properties);
        cipher.initialize();
    }

    @Test
    void roundTripsAnApiKeyWithoutLeakingItInCiphertext() {
        String encrypted = cipher.encrypt("sk-secret-value");

        assertThat(encrypted).isNotEqualTo("sk-secret-value");
        assertThat(encrypted).contains(".");
        assertThat(cipher.decrypt(encrypted)).isEqualTo("sk-secret-value");
    }

    @Test
    void producesDifferentCiphertextsForTheSameKey() {
        assertThat(cipher.encrypt("same-key")).isNotEqualTo(cipher.encrypt("same-key"));
    }

    @Test
    void rejectsTamperedCiphertext() {
        String encrypted = cipher.encrypt("sk-secret");
        String tampered = encrypted.substring(0, encrypted.length() - 1) + 'A';

        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesToStartWithAMissingOrMalformedKey() {
        properties = new ModelGatewayProperties();
        properties.setEnabled(true);
        properties.setEncryptionKey(null);
        ProviderKeyCipher bad = new ProviderKeyCipher(properties);

        assertThatThrownBy(bad::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MODEL_PROVIDER_ENCRYPTION_KEY");

        properties.setEncryptionKey("too-short");
        ProviderKeyCipher malformed = new ProviderKeyCipher(properties);
        assertThatThrownBy(malformed::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MODEL_PROVIDER_ENCRYPTION_KEY");
    }
}