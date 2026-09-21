package com.xander.lab.integration.host;

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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** CSDN Platform authorization lifecycle tests without a Selenium server or external account. */
@ExtendWith(MockitoExtension.class)
class PlatformCsdnAuthorizationServiceTest {

    @Mock
    private PlatformCsdnCredentialStore credentials;

    private PlatformCsdnProperties properties;
    private TestableService service;
    private RemoteWebDriver driver;

    @BeforeEach
    void setUp() {
        properties = new PlatformCsdnProperties();
        properties.setAuthorizationTimeoutSeconds(10);
        driver = mock(RemoteWebDriver.class);
        service = new TestableService(properties, credentials, driver);
    }

    @AfterEach
    void tearDown() {
        service.cancel(1L);
    }

    @Test
    void startsWithQrAndPersistsCookieAfterSuccessfulScan() {
        RemoteWebDriver.Options options = mock(RemoteWebDriver.Options.class);
        when(options.getCookies()).thenReturn(Set.of(
                new Cookie("UserToken", "token"),
                new Cookie("UserName", "tester")));
        when(driver.manage()).thenReturn(options);

        var pending = service.start(1L);
        var authorized = service.status(1L);

        assertThat(pending.status()).isEqualTo("PENDING");
        assertThat(pending.qrCodeDataUrl()).isEqualTo("data:image/png;base64,qr");
        assertThat(authorized.status()).isEqualTo("AUTHORIZED");
        verify(credentials).save(1L, "UserName=tester; UserToken=token", "tester");
        verify(driver).quit();
    }

    @Test
    void queuesAnotherUserAndPromotesThemAfterTheOwnerCancels() {
        assertThat(service.start(1L).status()).isEqualTo("PENDING");
        assertThat(service.start(2L).status()).isEqualTo("QUEUED");

        service.cancel(1L);
        var promoted = service.status(2L);

        assertThat(promoted.status()).isEqualTo("PENDING");
        assertThat(promoted.qrCodeDataUrl()).isEqualTo("data:image/png;base64,qr");
    }

    @Test
    void returnsStoredExpiredStateWhenNoSessionIsActive() {
        when(credentials.status(7L))
                .thenReturn(PlatformCsdnCredentialStore.CredentialStatus.EXPIRED);

        var view = service.status(7L);

        assertThat(view.status()).isEqualTo("EXPIRED");
    }

    private static final class TestableService extends PlatformCsdnAuthorizationService {
        private final RemoteWebDriver driver;

        private TestableService(PlatformCsdnProperties properties,
                                PlatformCsdnCredentialStore credentials,
                                RemoteWebDriver driver) {
            super(properties, credentials);
            this.driver = driver;
        }

        @Override
        protected RemoteWebDriver newDriver() {
            return driver;
        }

        @Override
        protected String waitForQrCode(RemoteWebDriver driver) {
            return "data:image/png;base64,qr";
        }
    }
}
