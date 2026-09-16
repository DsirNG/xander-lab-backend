package com.xander.lab.integration.mcp.juejin.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.integration.mcp.juejin.infrastructure.JuejinClient;
import com.xander.lab.integration.mcp.juejin.infrastructure.JuejinPlatformProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openqa.selenium.By;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.remote.RemoteWebDriver;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Concurrency behavior of the Juejin authorization slot, without a real Selenium server. */
@ExtendWith(MockitoExtension.class)
class JuejinAuthorizationServiceTest {

    private static final Long OWNER = 1L;
    private static final Long OTHER = 2L;
    private static final Long THIRD = 3L;

    @Mock JuejinCredentialService credentials;
    @Mock JuejinClient client;

    private JuejinPlatformProperties properties;
    private ExecutorService executor;
    private TestableService service;

    @BeforeEach
    void setUp() {
        properties = new JuejinPlatformProperties();
        properties.setAuthorizationTimeoutSeconds(10);
        service = new TestableService(properties, credentials, client);
        service.driver = mock(RemoteWebDriver.class);
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @Test
    void successfulStartReturnsQrCodeAndCanBeRestartedAfterCancel() {
        service.driver = mock(RemoteWebDriver.class);

        JuejinAuthorizationService.AuthorizationView view = service.start(OWNER);

        assertEquals("PENDING", view.status());
        assertEquals("data:image/png;base64,abc", view.qrCodeDataUrl());
        assertTrue(view.expiresInSeconds() != null && view.expiresInSeconds() <= 10);

        service.cancel(OWNER);
        verify(service.driver).quit();
        assertEquals("PENDING", service.start(OWNER).status());
    }

    @Test
    void cancelWhileStartingDisposesOrphanDriverAndFailsTheStarter() throws Exception {
        CountDownLatch enteredNewDriver = new CountDownLatch(1);
        CountDownLatch releaseNewDriver = new CountDownLatch(1);
        service.driver = mock(RemoteWebDriver.class);
        service.beforeNewDriver = () -> {
            enteredNewDriver.countDown();
            await(releaseNewDriver);
        };

        executor = Executors.newSingleThreadExecutor();
        var future = executor.submit(() -> service.start(OWNER));

        assertTrue(enteredNewDriver.await(5, TimeUnit.SECONDS), "driver setup never started");
        service.cancel(OWNER);
        releaseNewDriver.countDown();

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> unwrap(future));
        assertTrue(error.getMessage().contains("was cancelled while starting"));
        verify(service.driver).quit();
    }

    @Test
    void differentUserIsQueuedWhileAnotherUserIsStarting() throws Exception {
        CountDownLatch enteredNewDriver = new CountDownLatch(1);
        CountDownLatch releaseNewDriver = new CountDownLatch(1);
        service.driver = mock(RemoteWebDriver.class);
        service.beforeNewDriver = () -> {
            enteredNewDriver.countDown();
            await(releaseNewDriver);
        };

        executor = Executors.newSingleThreadExecutor();
        var future = executor.submit(() -> service.start(OWNER));

        assertTrue(enteredNewDriver.await(5, TimeUnit.SECONDS));
        JuejinAuthorizationService.AuthorizationView view = service.start(OTHER);
        assertEquals("QUEUED", view.status());
        assertEquals(1, view.queuePosition());

        releaseNewDriver.countDown();
        assertEquals("PENDING", future.get().status());
        service.cancel(OWNER);
    }

    @Test
    void queuedUserTakesOverTheSlotWhenItFrees() {
        service.driver = mock(RemoteWebDriver.class);
        assertEquals("PENDING", service.start(OWNER).status());

        assertEquals("QUEUED", service.start(OTHER).status());
        service.cancel(OWNER);

        JuejinAuthorizationService.AuthorizationView view = service.status(OTHER);
        assertEquals("PENDING", view.status());
        assertEquals("data:image/png;base64,abc", view.qrCodeDataUrl());
        service.cancel(OTHER);
    }

    @Test
    void cancelLeavesTheQueueAndAdvancesTheNextUser() {
        service.driver = mock(RemoteWebDriver.class);
        assertEquals("PENDING", service.start(OWNER).status());

        assertEquals(1, service.start(OTHER).queuePosition());
        assertEquals(2, service.start(THIRD).queuePosition());

        service.cancel(OTHER);
        assertEquals("QUEUED", service.status(THIRD).status());
        assertEquals(1, service.status(THIRD).queuePosition());

        service.cancel(OWNER);
        assertEquals("PENDING", service.status(THIRD).status());
        service.cancel(THIRD);
    }

    @Test
    void scheduledCleanupDisposesExpiredSessionAndFreesTheSlot() {
        properties.setAuthorizationTimeoutSeconds(-5);
        service.driver = mock(RemoteWebDriver.class);
        assertEquals("PENDING", service.start(OWNER).status());

        service.cleanupExpiredSessions();

        verify(service.driver).quit();
        assertEquals("NOT_AUTHORIZED", service.status(OWNER).status());
        assertEquals("PENDING", service.start(OWNER).status());
    }

    @Test
    void failedTakeoverRequeuesAtTheFrontForTheNextPoll() {
        service.driver = mock(RemoteWebDriver.class);
        assertEquals("PENDING", service.start(OWNER).status());
        assertEquals("QUEUED", service.start(OTHER).status());

        service.cancel(OWNER);
        service.failNewDriver = true;
        JuejinAuthorizationService.AuthorizationView view = service.status(OTHER);
        assertEquals("QUEUED", view.status());
        assertEquals(1, view.queuePosition());

        service.failNewDriver = false;
        assertEquals("PENDING", service.status(OTHER).status());
        service.cancel(OTHER);
    }

    @Test
    void failedQrCodeWaitReleasesTheSlotForTheNextUser() {
        service.driver = mock(RemoteWebDriver.class);
        service.failQrCode = true;

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.start(OWNER));
        assertTrue(error.getMessage().contains("Unable to start Juejin authorization"));

        service.failQrCode = false;
        assertEquals("PENDING", service.start(OWNER).status());
    }

    @Test
    void statusKeepsPendingWhileQrIsStillVisible() {
        service.driver = qrVisibleDriver();
        assertEquals("PENDING", service.start(OWNER).status());

        JuejinAuthorizationService.AuthorizationView view = service.status(OWNER);

        assertEquals("PENDING", view.status());
        assertNull(view.qrCodeDataUrl());
        assertEquals(1, service.qrWaitCalls);
    }

    @Test
    void statusReloadsQrWhenItDisappearsWithoutLoginAndStaysPending() {
        service.driver = qrGoneDriver();
        service.failQrOnRefresh = true;
        assertEquals("PENDING", service.start(OWNER).status());

        JuejinAuthorizationService.AuthorizationView view = service.status(OWNER);

        assertEquals("PENDING", view.status());
        assertNull(view.qrCodeDataUrl());
        assertEquals(2, service.qrWaitCalls);
        service.cancel(OWNER);
    }

    @Test
    void statusAuthorizesAndPersistsProfileWhenSessionCookieIsPresent() {
        service.driver = mock(RemoteWebDriver.class);
        RemoteWebDriver.Options options = mock(RemoteWebDriver.Options.class);
        when(options.getCookies()).thenReturn(Set.of(
                new Cookie("sessionid", "sess-1"),
                new Cookie("sessionid_ss", "sess-2"),
                new Cookie("passport_csrf_token", "csrf-1"),
                new Cookie("uuid", "7673800314282395162"),
                new Cookie("Other", "x")
        ));
        when(service.driver.manage()).thenReturn(options);
        when(client.fetchUserProfile(any())).thenReturn(profileResponse());
        assertEquals("PENDING", service.start(OWNER).status());

        JuejinAuthorizationService.AuthorizationView view = service.status(OWNER);

        assertEquals("AUTHORIZED", view.status());
        verify(credentials).save(eq(OWNER), any(), eq("XanderDING"));
        verify(service.driver).quit();
    }

    @Test
    void statusStillAuthorizesWhenProfileFetchFails() {
        service.driver = mock(RemoteWebDriver.class);
        RemoteWebDriver.Options options = mock(RemoteWebDriver.Options.class);
        when(options.getCookies()).thenReturn(Set.of(new Cookie("sessionid", "sess-1")));
        when(service.driver.manage()).thenReturn(options);
        when(client.fetchUserProfile(any())).thenThrow(new JuejinClient.JuejinApiException(-1, "boom"));
        assertEquals("PENDING", service.start(OWNER).status());

        JuejinAuthorizationService.AuthorizationView view = service.status(OWNER);

        assertEquals("AUTHORIZED", view.status());
        verify(credentials).save(eq(OWNER), any(), nullable(String.class));
        verify(service.driver).quit();
    }

    @Test
    void statusReportsExpiredWhenStoredCredentialLostValidity() {
        when(credentials.isAuthorized(OWNER)).thenReturn(false);
        when(credentials.isExpired(OWNER)).thenReturn(true);

        JuejinAuthorizationService.AuthorizationView view = service.status(OWNER);

        assertEquals("EXPIRED", view.status());
        assertNull(view.qrCodeDataUrl());
    }

    private static JsonNode profileResponse() {
        return new ObjectMapper().createObjectNode().set("data", new ObjectMapper().createObjectNode()
                .put("user_id", "2587590326494763")
                .put("sec_user_id", "sec-1")
                .put("user_name", "XanderDING")
                .put("avatar_large", "https://avatar.example/x.png")
                .put("phone", "19*****2608"));
    }

    private RemoteWebDriver qrVisibleDriver() {
        RemoteWebDriver driver = mock(RemoteWebDriver.class);
        WebElement qr = mock(WebElement.class);
        when(qr.isDisplayed()).thenReturn(true);
        when(driver.findElements(any(By.class))).thenReturn(List.of(qr));
        RemoteWebDriver.Options options = mock(RemoteWebDriver.Options.class);
        when(options.getCookies()).thenReturn(Set.of());
        when(driver.manage()).thenReturn(options);
        return driver;
    }

    private RemoteWebDriver qrGoneDriver() {
        RemoteWebDriver driver = mock(RemoteWebDriver.class);
        when(driver.findElements(any(By.class))).thenReturn(List.of());
        RemoteWebDriver.Options options = mock(RemoteWebDriver.Options.class);
        when(options.getCookies()).thenReturn(Set.of());
        when(driver.manage()).thenReturn(options);
        return driver;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test latch timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static Object unwrap(java.util.concurrent.Future<?> future) {
        try {
            return future.get();
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Service exposing seams around Selenium so tests never open a real driver connection. */
    private static class TestableService extends JuejinAuthorizationService {
        RemoteWebDriver driver;
        Runnable beforeNewDriver;
        boolean failNewDriver;
        boolean failQrCode;
        boolean failQrOnRefresh;
        int qrWaitCalls;

        TestableService(JuejinPlatformProperties properties, JuejinCredentialService credentials, JuejinClient client) {
            super(properties, credentials, client);
        }

        @Override
        protected RemoteWebDriver newDriver() {
            if (beforeNewDriver != null) beforeNewDriver.run();
            if (failNewDriver) throw new IllegalStateException("selenium down");
            return driver;
        }

        @Override
        protected String waitForQrCode(RemoteWebDriver driver) {
            qrWaitCalls++;
            if (failQrCode || (failQrOnRefresh && qrWaitCalls >= 2)) throw new IllegalStateException("boom");
            return "data:image/png;base64,abc";
        }
    }
}
