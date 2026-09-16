package com.xander.lab.identity.infrastructure.wechat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WechatCredentialCipherTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private WechatCredentialProperties properties;
    private WechatCredentialCipher cipher;

    @BeforeEach
    void setUp() {
        properties = new WechatCredentialProperties();
        properties.setEncryptionKey(KEY);
        cipher = new WechatCredentialCipher(properties);
        cipher.initialize();
    }

    @Test
    void roundTripsAnAppSecretWithoutLeakingItInCiphertext() {
        String encrypted = cipher.encrypt("wx-secret-value");

        assertThat(encrypted).isNotEqualTo("wx-secret-value");
        assertThat(encrypted).contains(".");
        assertThat(cipher.decrypt(encrypted)).isEqualTo("wx-secret-value");
    }

    @Test
    void producesDifferentCiphertextsForTheSameSecret() {
        assertThat(cipher.encrypt("same-secret")).isNotEqualTo(cipher.encrypt("same-secret"));
    }

    @Test
    void rejectsTamperedCiphertext() {
        String encrypted = cipher.encrypt("wx-secret");
        String tampered = encrypted.substring(0, encrypted.length() - 1) + 'A';

        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void staysInactiveWhenKeyIsMissing() {
        WechatCredentialProperties empty = new WechatCredentialProperties();
        empty.setEncryptionKey("");
        WechatCredentialCipher inactive = new WechatCredentialCipher(empty);
        inactive.initialize();

        assertThat(inactive.isReady()).isFalse();
        assertThatThrownBy(() -> inactive.encrypt("secret"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("微信登录未配置");
    }

    @Test
    void refusesToStartWithMalformedKey() {
        properties.setEncryptionKey("too-short");
        WechatCredentialCipher malformed = new WechatCredentialCipher(properties);

        assertThatThrownBy(malformed::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WECHAT_CREDENTIAL_ENCRYPTION_KEY");
    }
}