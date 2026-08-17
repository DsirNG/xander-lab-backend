package com.xander.lab.identity.application;

import com.xander.lab.identity.api.WechatCredentialView;
import com.xander.lab.identity.domain.WechatCredential;
import com.xander.lab.identity.infrastructure.WechatCredentialMapper;
import com.xander.lab.identity.infrastructure.wechat.WechatCredentialCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WechatCredentialServiceTest {

    @Mock
    private WechatCredentialMapper mapper;

    @Mock
    private WechatCredentialCipher cipher;

    private WechatCredentialService service;

    @BeforeEach
    void setUp() {
        service = new WechatCredentialService(mapper, cipher);
    }

    @Test
    void adminViewShowsNotConfiguredWhenNoRowExists() {
        when(mapper.selectById(1L)).thenReturn(null);

        WechatCredentialView view = service.getAdminView();

        assertThat(view.appId()).isNull();
        assertThat(view.secretConfigured()).isFalse();
    }

    @Test
    void saveEncryptsSecretAndStoresFixedIdRow() {
        when(mapper.selectById(1L)).thenReturn(null);
        when(cipher.isReady()).thenReturn(true);
        when(cipher.encrypt("wx-secret")).thenReturn("cipher.0");

        service.save("wx-app-id", "wx-secret");

        verify(mapper).insert(any(WechatCredential.class));
        verify(mapper, never()).updateById(any());
    }

    @Test
    void saveUpdatesExistingRowInsteadOfInserting() {
        WechatCredential existing = row("wx-old-id", "old-cipher");
        when(mapper.selectById(1L)).thenReturn(existing);
        when(cipher.isReady()).thenReturn(true);
        when(cipher.encrypt("new-secret")).thenReturn("new-cipher");

        service.save("wx-new-id", "new-secret");

        verify(mapper, never()).insert(any());
        verify(mapper).updateById(any(WechatCredential.class));
    }

    @Test
    void saveRejectsBlankFields() {
        assertThatThrownBy(() -> service.save("  ", "secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AppID");
        assertThatThrownBy(() -> service.save("app-id", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AppSecret");
        verify(mapper, never()).insert(any());
    }

    @Test
    void saveRefusesWhenEncryptionKeyIsMissing() {
        when(cipher.isReady()).thenReturn(false);

        assertThatThrownBy(() -> service.save("app-id", "secret"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode().value())
                .isEqualTo(HttpStatus.BAD_REQUEST.value());
        verify(mapper, never()).insert(any());
    }

    @Test
    void clearDeletesTheFixedRow() {
        service.clear();

        verify(mapper).deleteById(1L);
    }

    @Test
    void resolveReturnsDecryptedCredential() {
        when(mapper.selectById(1L)).thenReturn(row("wx-app-id", "cipher.0"));
        when(cipher.decrypt("cipher.0")).thenReturn("wx-secret");

        WechatCredentialService.ResolvedCredential resolved = service.resolve();

        assertThat(resolved.appId()).isEqualTo("wx-app-id");
        assertThat(resolved.appSecret()).isEqualTo("wx-secret");
    }

    @Test
    void resolveReturnsNullWhenNoCredentialConfigured() {
        when(mapper.selectById(1L)).thenReturn(null);

        assertThat(service.resolve()).isNull();
    }

    @Test
    void adminViewNeverExposesTheEncryptedSecret() {
        when(mapper.selectById(1L)).thenReturn(row("wx-app-id", "cipher.0"));

        WechatCredentialView view = service.getAdminView();

        assertThat(view.appId()).isEqualTo("wx-app-id");
        assertThat(view.secretConfigured()).isTrue();
        assertThat(view.toString()).doesNotContain("cipher.0");
    }

    private WechatCredential row(String appId, String encryptedSecret) {
        WechatCredential row = new WechatCredential();
        row.setId(1L);
        row.setAppId(appId);
        row.setEncryptedAppSecret(encryptedSecret);
        row.setUpdatedAt(LocalDateTime.now());
        return row;
    }
}