package com.xander.lab.integration.mcp.csdn.application;

import com.xander.lab.integration.mcp.csdn.infrastructure.CsdnProperties;
import lombok.RequiredArgsConstructor;
import org.openqa.selenium.By;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Collectors;

/** Owns the single short-lived Selenium session used for CSDN QR authorization. */
@Service
@RequiredArgsConstructor
public class CsdnAuthorizationService {
    private final CsdnProperties properties;
    private final CsdnCredentialService credentials;

    /** The single authorization slot. Field mutations are guarded by {@code this}; Selenium calls never hold it. */
    private volatile Session session;

    public AuthorizationView start(Long userId) {
        RemoteWebDriver driver = claimPendingSession(userId);
        try {
            return new AuthorizationView("PENDING", waitForQrCode(driver));
        } catch (Exception e) {
            releasePendingSession(userId, driver);
            throw new IllegalStateException("Unable to start CSDN authorization", e);
        }
    }

    /** Navigates to the CSDN login frame and returns the QR code data URL. Test seams override this and {@link #newDriver()}. */
    protected String waitForQrCode(RemoteWebDriver driver) {
        driver.get("https://mp.csdn.net/");
        WebElement frame = new WebDriverWait(driver, Duration.ofSeconds(20))
                .until(d -> d.findElement(By.cssSelector("iframe[src*='passport.csdn.net']")));
        driver.switchTo().frame(frame);
        String qrCodeDataUrl = new WebDriverWait(driver, Duration.ofSeconds(20)).until(d -> {
            for (WebElement image : d.findElements(By.cssSelector(".public-code img[src^='data:image/']"))) {
                String source = image.getAttribute("src");
                if (image.isDisplayed() && source != null && source.startsWith("data:image/")) return source;
            }
            return null;
        });
        driver.switchTo().defaultContent();
        return qrCodeDataUrl;
    }

    /**
     * Places a placeholder slot, then starts the Selenium driver outside the slot monitor so a slow
     * driver setup never blocks other users. Only the last owner is accepted; a cancelled slot releases
     * the orphan driver.
     */
    private RemoteWebDriver claimPendingSession(Long userId) {
        Instant expiry;
        synchronized (this) {
            closeExpired();
            Session current = session;
            if (current != null) {
                if (!current.userId().equals(userId)) {
                    throw new IllegalStateException("Another CSDN authorization is in progress");
                }
                if (current.driver() == null) {
                    throw new IllegalStateException("CSDN authorization is already starting");
                }
                throw new IllegalStateException("CSDN authorization is already active");
            }
            expiry = Instant.now().plusSeconds(properties.getAuthorizationTimeoutSeconds());
            session = new Session(userId, null, expiry);
        }
        RemoteWebDriver driver;
        try {
            driver = newDriver();
        } catch (Exception e) {
            synchronized (this) {
                if (session != null && session.userId().equals(userId) && session.driver() == null) session = null;
            }
            throw new IllegalStateException("Unable to start CSDN authorization", e);
        }
        synchronized (this) {
            if (session == null || !session.userId().equals(userId) || session.driver() != null) {
                try { driver.quit(); } catch (Exception ignored) { }
                throw new IllegalStateException("CSDN authorization was cancelled while starting");
            }
            session = new Session(userId, driver, expiry);
        }
        return driver;
    }

    private void releasePendingSession(Long userId, RemoteWebDriver driver) {
        boolean removed;
        synchronized (this) {
            removed = session != null && session.userId().equals(userId) && session.driver() == driver;
            if (removed) session = null;
        }
        if (removed) dispose(driver);
    }

    public AuthorizationView status(Long userId) {
        Session mine;
        synchronized (this) {
            closeExpired();
            Session current = session;
            if (current == null) {
                return new AuthorizationView(credentials.isAuthorized(userId) ? "AUTHORIZED" : "NOT_AUTHORIZED", null);
            }
            if (!current.userId().equals(userId)) {
                return new AuthorizationView(credentials.isAuthorized(userId) ? "AUTHORIZED" : "NOT_AUTHORIZED", null);
            }
            if (current.driver() == null) {
                // Placeholder means the Selenium session is still being prepared.
                return new AuthorizationView("PENDING", null);
            }
            mine = current;
        }
        boolean loginFrameVisible = mine.driver().findElements(By.cssSelector("iframe[src*='passport.csdn.net/account/login']"))
                .stream().anyMatch(element -> {
                    try { return element.isDisplayed(); } catch (Exception ignored) { return false; }
                });
        if (loginFrameVisible) return new AuthorizationView("PENDING", null);
        mine.driver().navigate().refresh();
        new WebDriverWait(mine.driver(), Duration.ofSeconds(20)).until(driver ->
                "complete".equals(((org.openqa.selenium.JavascriptExecutor) driver).executeScript("return document.readyState")));
        Set<Cookie> cookies = mine.driver().manage().getCookies();
        String token = cookies.stream().filter(cookie -> "UserToken".equals(cookie.getName()))
                .map(Cookie::getValue).findFirst().orElse(null);
        if (token == null || token.isBlank()) return new AuthorizationView("PENDING", null);
        String header = cookies.stream().sorted(Comparator.comparing(Cookie::getName))
                .map(cookie -> cookie.getName() + "=" + cookie.getValue()).collect(Collectors.joining("; "));
        String username = cookies.stream().filter(cookie -> "UserName".equals(cookie.getName()))
                .map(Cookie::getValue).findFirst().orElse(null);
        credentials.save(userId, header, username);
        closeOwnedSession(userId, mine);
        return new AuthorizationView("AUTHORIZED", null);
    }

    public void disconnect(Long userId) {
        closeSessionFor(userId);
        credentials.delete(userId);
    }

    /** Closes the pending authorization session for this user without deleting stored credentials. */
    public void cancel(Long userId) {
        closeSessionFor(userId);
    }

    private void closeOwnedSession(Long userId, Session owned) {
        synchronized (this) {
            if (session != null && session.userId().equals(userId) && session.driver() == owned.driver()) session = null;
        }
        dispose(owned.driver());
    }

    private void closeSessionFor(Long userId) {
        Session gone;
        synchronized (this) {
            closeExpired();
            if (session == null || !session.userId().equals(userId)) return;
            gone = session;
            session = null;
        }
        dispose(gone.driver());
    }

    private void closeExpired() {
        if (session != null && Instant.now().isAfter(session.expiresAt())) {
            Session gone = session;
            session = null;
            dispose(gone.driver());
        }
    }

    protected RemoteWebDriver newDriver() throws Exception {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new", "--no-sandbox", "--disable-dev-shm-usage", "--window-size=1360,1020");
        return new RemoteWebDriver(URI.create(properties.getSeleniumUrl()).toURL(), options);
    }

    private void dispose(RemoteWebDriver driver) {
        if (driver == null) return;
        try { driver.quit(); } catch (Exception ignored) { }
    }

    private record Session(Long userId, RemoteWebDriver driver, Instant expiresAt) { }
    public record AuthorizationView(String status, String qrCodeDataUrl) { }
}