package com.xander.lab.integration.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.remote.RemoteWebDriver;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Juejin Platform authorization lifecycle tests without a Selenium server or external account. */
@ExtendWith(MockitoExtension.class)
class PlatformJuejinAuthorizationServiceTest {

    @Mock
    private PlatformJuejinCredentialStore credentials;

    @Mock
    private PlatformJuejinClient client;

    private PlatformJuejinProperties properties;
    private TestableService service;
    private RemoteWebDriver driver;

    @BeforeEach
    void setUp() {
        properties = new PlatformJuejinProperties();
        properties.setAuthorizationTimeoutSeconds(10);
        driver = mock(RemoteWebDriver.class);
        service = new TestableService(properties, credentials, client, driver);
    }

    @AfterEach
    void tearDown() {
        service.cancel(1L);
    }

    @Test
    void startsWithQrAndPersistsCookieAndProfileAfterSuccessfulScan() {
        RemoteWebDriver.Options options = mock(RemoteWebDriver.Options.class);
        when(options.getCookies()).thenReturn(Set.of(
                new Cookie("sessionid", "session"),
                new Cookie("passport_csrf_token", "csrf"),
                new Cookie("uuid", "uuid")));
        when(driver.manage()).thenReturn(options);
        var profile = new ObjectMapper().createObjectNode();
        profile.putObject("data").put("user_name", "tester");
        when(client.fetchUserProfile(any())).thenReturn(profile);

        var pending = service.start(1L);
        var authorized = service.status(1L);

        assertThat(pending.status()).isEqualTo("PENDING");
        assertThat(pending.qrCodeDataUrl()).isEqualTo("data:image/png;base64:qr");
        assertThat(authorized.status()).isEqualTo("AUTHORIZED");
        verify(credentials).save(eq(1L), any(PlatformJuejinClient.Credential.class), eq("tester"));
        verify(driver).quit();
    }

    @Test
    void returnsStoredExpiredStateWhenNoSessionIsActive() {
        when(credentials.status(7L))
                .thenReturn(PlatformJuejinCredentialStore.CredentialStatus.EXPIRED);

        var view = service.status(7L);

        assertThat(view.status()).isEqualTo("EXPIRED");
    }

    private static final class TestableService extends PlatformJuejinAuthorizationService {
        private final RemoteWebDriver driver;

        private TestableService(PlatformJuejinProperties properties,
                                PlatformJuejinCredentialStore credentials,
                                PlatformJuejinClient client,
                                RemoteWebDriver driver) {
            super(properties, credentials, client);
            this.driver = driver;
        }

        @Override
        protected RemoteWebDriver newDriver() {
            return driver;
        }

        @Override
        protected String waitForQrCode(RemoteWebDriver driver) {
            return "data:image/png;base64:qr";
        }
    }
}
