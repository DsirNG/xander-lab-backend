package com.xander.lab.integration.mcp.juejin.application;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.xander.lab.integration.mcp.juejin.infrastructure.JuejinClient;
import com.xander.lab.integration.mcp.juejin.infrastructure.JuejinPlatformCredential;
import com.xander.lab.integration.mcp.juejin.infrastructure.JuejinPlatformCredentialMapper;
import com.xander.lab.integration.mcp.juejin.infrastructure.JuejinPlatformProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JuejinCredentialServiceTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Mock JuejinPlatformCredentialMapper mapper;

    JuejinPlatformProperties properties = new JuejinPlatformProperties();
    JuejinCredentialService service;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        properties.setCredentialEncryptionKey(KEY);
        service = new JuejinCredentialService(mapper, properties, new ObjectMapper());
        service.initialize();
    }

    @Test
    void saveUpsertsAnEncryptedCredentialWithoutAPreRead() {
        service.save(7L, new JuejinClient.Credential("cookie=abc", "csrf-token", "2608", "uuid-1"), "掘金用户");

        ArgumentCaptor<JuejinPlatformCredential> captured = ArgumentCaptor.forClass(JuejinPlatformCredential.class);
        verify(mapper).upsert(captured.capture());
        verify(mapper, never()).selectOne(any());
        JuejinPlatformCredential stored = captured.getValue();
        assertEquals(7L, stored.getUserId());
        assertEquals("掘金用户", stored.getJuejinUsername());
        assertEquals("ACTIVE", stored.getStatus());
        assertNotEquals("cookie=abc", stored.getEncryptedCookie());
        assertTrue(stored.getEncryptedCookie().contains("."));
    }

    @Test
    void requireCredentialDecryptsWhatSaveEncrypted() {
        service.save(7L, new JuejinClient.Credential("cookie=abc", "csrf-token", "2608", "uuid-1"), "u");
        ArgumentCaptor<JuejinPlatformCredential> captured = ArgumentCaptor.forClass(JuejinPlatformCredential.class);
        verify(mapper).upsert(captured.capture());
        when(mapper.selectOne(any())).thenReturn(captured.getValue());

        JuejinClient.Credential read = service.requireCredential(7L);
        assertEquals("cookie=abc", read.cookie());
        assertEquals("csrf-token", read.csrfToken());
        assertEquals("2608", read.aid());
        assertEquals("uuid-1", read.uuid());
    }

    @Test
    void isAuthorizedRequiresActiveEncryptedCredential() {
        JuejinPlatformCredential full = new JuejinPlatformCredential();
        full.setEncryptedCookie("x"); full.setStatus("ACTIVE");
        when(mapper.selectOne(any())).thenReturn(full);
        assertTrue(service.isAuthorized(1L));

        JuejinPlatformCredential partial = new JuejinPlatformCredential();
        partial.setStatus("ACTIVE");
        when(mapper.selectOne(any())).thenReturn(partial);
        assertFalse(service.isAuthorized(1L));
    }

    @Test
    void expiredCredentialIsNotAuthorizedButReportedAsExpired() {
        JuejinPlatformCredential expired = new JuejinPlatformCredential();
        expired.setEncryptedCookie("x"); expired.setStatus("EXPIRED");
        when(mapper.selectOne(any())).thenReturn(expired);

        assertFalse(service.isAuthorized(1L));
        assertTrue(service.isExpired(1L));

        when(mapper.selectOne(any())).thenReturn(new JuejinPlatformCredential());
        assertFalse(service.isExpired(1L));
    }

    @Test
    void markExpiredIssuesConditionalStatusUpdate() {
        service.markExpired(7L);

        verify(mapper).update(isNull(), any(Wrapper.class));
    }

    @Test
    void initializeRejectsInvalidEncryptionKey() {
        JuejinCredentialService broken = new JuejinCredentialService(mapper, properties, new ObjectMapper());
        properties.setCredentialEncryptionKey("not-base64");
        assertThrows(IllegalStateException.class, broken::initialize);
    }
}