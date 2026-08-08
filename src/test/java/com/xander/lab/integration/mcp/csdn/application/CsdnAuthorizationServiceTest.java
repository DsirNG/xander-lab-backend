package com.xander.lab.integration.mcp.csdn.application;

import com.xander.lab.integration.mcp.csdn.infrastructure.CsdnProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.remote.RemoteWebDriver;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Concurrency behavior of the CSDN authorization slot, without a real Selenium server. */
@ExtendWith(MockitoExtension.class)
class CsdnAuthorizationServiceTest {

    private static final Long OWNER = 1L;
    private static final Long OTHER = 2L;
    private static final Long THIRD = 3L;

    @Mock CsdnCredentialService credentials;

    private ExecutorService executor;
    private TestableService service;

    @BeforeEach
    void setUp() {
        CsdnProperties properties = new CsdnProperties();
        properties.setAuthorizationTimeoutSeconds(10);
        service = new TestableService(properties, credentials);
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

        CsdnAuthorizationService.AuthorizationView view = service.start(OWNER);

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
    void statusReportsPendingWhileSessionIsBeingPrepared() throws Exception {
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
        CsdnAuthorizationService.AuthorizationView view = service.status(OWNER);
        assertEquals("PENDING", view.status());
        assertNull(view.qrCodeDataUrl());

        releaseNewDriver.countDown();
        assertEquals("PENDING", future.get().status());
        service.cancel(OWNER);
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
        CsdnAuthorizationService.AuthorizationView view = service.start(OTHER);
        assertEquals("QUEUED", view.status());
        assertEquals(1, view.queuePosition());
        assertTrue(view.estimatedWaitSeconds() != null && view.estimatedWaitSeconds() >= 0);

        releaseNewDriver.countDown();
        assertEquals("PENDING", future.get().status());
        service.cancel(OWNER);
    }

    @Test
    void statusOfDifferentUserIsNotBlockedWhileAnotherSessionIsStarting() throws Exception {
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
        CsdnAuthorizationService.AuthorizationView view = service.status(OTHER);
        assertEquals("NOT_AUTHORIZED", view.status());

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

        CsdnAuthorizationService.AuthorizationView view = service.status(OTHER);
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
    void failedTakeoverRequeuesAtTheFrontForTheNextPoll() {
        service.driver = mock(RemoteWebDriver.class);
        assertEquals("PENDING", service.start(OWNER).status());
        assertEquals("QUEUED", service.start(OTHER).status());

        service.cancel(OWNER);
        service.failNewDriver = true;
        CsdnAuthorizationService.AuthorizationView view = service.status(OTHER);
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
        assertTrue(error.getMessage().contains("Unable to start CSDN authorization"));

        service.failQrCode = false;
        assertEquals("PENDING", service.start(OWNER).status());
    }

    @Test
    void statusRefreshesExpiredQrAndReturnsFreshDataUrl() {
        RemoteWebDriver driver = loginFrameVisibleDriver();
        service.driver = driver;
        service.qrExpired = true;
        assertEquals("PENDING", service.start(OWNER).status());

        CsdnAuthorizationService.AuthorizationView view = service.status(OWNER);

        assertEquals("PENDING", view.status());
        assertEquals("data:image/png;base64,abc", view.qrCodeDataUrl());
        assertEquals(2, service.qrWaitCalls);
    }

    @Test
    void statusKeepsPendingWithoutRefreshingWhenQrIsStillValid() {
        RemoteWebDriver driver = loginFrameVisibleDriver();
        service.driver = driver;
        service.qrExpired = false;
        assertEquals("PENDING", service.start(OWNER).status());

        CsdnAuthorizationService.AuthorizationView view = service.status(OWNER);

        assertEquals("PENDING", view.status());
        assertNull(view.qrCodeDataUrl());
        assertEquals(1, service.qrWaitCalls);
    }

    @Test
    void statusKeepsModalOpenWhenRefreshingExpiredQrFails() {
        RemoteWebDriver driver = loginFrameVisibleDriver();
        service.driver = driver;
        service.qrExpired = true;
        service.failQrOnRefresh = true;
        assertEquals("PENDING", service.start(OWNER).status());

        CsdnAuthorizationService.AuthorizationView view = service.status(OWNER);

        assertEquals("PENDING", view.status());
        assertNull(view.qrCodeDataUrl());
    }

    private RemoteWebDriver loginFrameVisibleDriver() {
        RemoteWebDriver driver = mock(RemoteWebDriver.class);
        WebElement loginFrame = mock(WebElement.class);
        when(loginFrame.isDisplayed()).thenReturn(true);
        when(driver.findElements(any(By.class))).thenReturn(List.of(loginFrame));
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
    private static class TestableService extends CsdnAuthorizationService {
        RemoteWebDriver driver;
        Runnable beforeNewDriver;
        boolean failNewDriver;
        boolean failQrCode;
        boolean failQrOnRefresh;
        boolean qrExpired;
        int qrWaitCalls;

        TestableService(CsdnProperties properties, CsdnCredentialService credentials) {
            super(properties, credentials);
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

        @Override
        protected boolean isQrExpired(RemoteWebDriver driver) {
            return qrExpired;
        }
    }
}