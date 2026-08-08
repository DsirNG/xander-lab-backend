package com.xander.lab.integration.mcp.csdn.application;

import com.xander.lab.integration.mcp.csdn.infrastructure.CsdnPlatformCredential;
import com.xander.lab.integration.mcp.csdn.infrastructure.CsdnPlatformCredentialMapper;
import com.xander.lab.integration.mcp.csdn.infrastructure.CsdnProperties;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CsdnCredentialServiceTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Mock CsdnPlatformCredentialMapper mapper;

    CsdnProperties properties = new CsdnProperties();
    CsdnCredentialService service;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        properties.setCredentialEncryptionKey(KEY);
        service = new CsdnCredentialService(mapper, properties);
        service.initialize();
    }

    @Test
    void saveUpsertsAnEncryptedCredentialWithoutAPreRead() {
        service.save(7L, "raw-cookie", "csdn-user");

        ArgumentCaptor<CsdnPlatformCredential> captured = ArgumentCaptor.forClass(CsdnPlatformCredential.class);
        verify(mapper).upsert(captured.capture());
        verify(mapper, never()).selectOne(any());
        CsdnPlatformCredential stored = captured.getValue();
        assertEquals(7L, stored.getUserId());
        assertEquals("csdn-user", stored.getCsdnUsername());
        assertEquals("ACTIVE", stored.getStatus());
        assertNotEquals("raw-cookie", stored.getEncryptedCookie());
        assertTrue(stored.getEncryptedCookie().contains("."));
    }

    @Test
    void requireCookieDecryptsWhatSaveEncrypted() {
        service.save(7L, "raw-cookie", "csdn-user");
        ArgumentCaptor<CsdnPlatformCredential> captured = ArgumentCaptor.forClass(CsdnPlatformCredential.class);
        verify(mapper).upsert(captured.capture());
        when(mapper.selectOne(any())).thenReturn(captured.getValue());

        assertEquals("raw-cookie", service.requireCookie(7L));
    }

    @Test
    void isAuthorizedRequiresCookieAndUsername() {
        CsdnPlatformCredential full = new CsdnPlatformCredential();
        full.setEncryptedCookie("x"); full.setCsdnUsername("u");
        when(mapper.selectOne(any())).thenReturn(full);
        assertTrue(service.isAuthorized(1L));

        CsdnPlatformCredential partial = new CsdnPlatformCredential();
        partial.setEncryptedCookie("x");
        when(mapper.selectOne(any())).thenReturn(partial);
        assertFalse(service.isAuthorized(1L));
    }

    @Test
    void initializeRejectsInvalidEncryptionKey() {
        CsdnCredentialService broken = new CsdnCredentialService(mapper, properties);
        properties.setCredentialEncryptionKey("not-base64");
        assertThrows(IllegalStateException.class, broken::initialize);
    }
}
