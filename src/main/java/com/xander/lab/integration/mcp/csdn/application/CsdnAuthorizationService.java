package com.xander.lab.integration.mcp.csdn.application;

import com.xander.lab.integration.mcp.csdn.infrastructure.CsdnProperties;
import lombok.RequiredArgsConstructor;
import org.openqa.selenium.By;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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

    /** FIFO wait queue for users blocked by a busy slot. Mutations are guarded by {@code this}. */
    private final List<Queued> queue = new ArrayList<>();

    /**
     * Starts the QR flow. Claims the slot when it is free; otherwise queues this user FIFO and reports
     * their position and expected wait. Re-requesting while queued moves the user to the back of the queue.
     */
    public AuthorizationView start(Long userId) {
        RemoteWebDriver discarded;
        Instant expiry = null;
        AuthorizationView busyView;
        synchronized (this) {
            discarded = closeExpired();
            Session current = session;
            if (current != null) {
                if (current.userId().equals(userId)) {
                    if (current.driver() == null) {
                        throw new IllegalStateException("CSDN authorization is already starting");
                    }
                    throw new IllegalStateException("CSDN authorization is already active");
                }
                busyView = enqueue(userId);
            } else {
                busyView = null;
                expiry = Instant.now().plusSeconds(properties.getAuthorizationTimeoutSeconds());
                session = new Session(userId, null, expiry);
            }
        }
        dispose(discarded);
        if (busyView != null) return busyView;
        RemoteWebDriver driver;
        try {
            driver = newDriver();
        } catch (Exception e) {
            synchronized (this) {
                if (session != null && session.userId().equals(userId) && session.driver() == null) session = null;
            }
            throw new IllegalStateException("Unable to start CSDN authorization", e);
        }
        RemoteWebDriver orphan;
        synchronized (this) {
            if (session == null || !session.userId().equals(userId) || session.driver() != null) {
                orphan = driver;
            } else {
                orphan = null;
                session = new Session(userId, driver, expiry);
            }
        }
        if (orphan != null) {
            dispose(orphan);
            throw new IllegalStateException("CSDN authorization was cancelled while starting");
        }
        try {
            return new AuthorizationView("PENDING", waitForQrCode(driver), remainingSeconds());
        } catch (Exception e) {
            releasePendingSession(userId, driver);
            throw new IllegalStateException("Unable to start CSDN authorization", e);
        }
    }

    /** Seconds the current authorization slot still accepts a scan; null when no slot is held. */
    private Integer remainingSeconds() {
        Session current = session;
        if (current == null) return null;
        return (int) Math.max(1, Duration.between(Instant.now(), current.expiresAt()).getSeconds());
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
     * Returns true when the CSDN login page shows the expired-QR placeholder instead of a scannable image.
     * Test seams override this and {@link #waitForQrCode(RemoteWebDriver)}.
     */
    protected boolean isQrExpired(RemoteWebDriver driver) {
        try {
            WebElement frame = new WebDriverWait(driver, Duration.ofSeconds(5))
                    .until(d -> d.findElement(By.cssSelector("iframe[src*='passport.csdn.net']")));
            driver.switchTo().frame(frame);
            boolean expired = driver.findElements(By.cssSelector("span.fail-mark, .fail-mark")).stream()
                    .anyMatch(element -> {
                        try { return element.isDisplayed(); } catch (Exception ignored) { return false; }
                    });
            driver.switchTo().defaultContent();
            return expired;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Puts the user at the back of the FIFO wait queue (re-queueing removes any earlier entry) and
     * reports their position plus the estimated wait in seconds.
     */
    private AuthorizationView enqueue(Long userId) {
        removeFromQueue(userId);
        queue.add(new Queued(userId, Instant.now().plusSeconds(properties.getAuthorizationTimeoutSeconds())));
        return queuedView(userId);
    }

    /** Builds the QUEUED view for a user: position in line plus estimated seconds until their turn. */
    private AuthorizationView queuedView(Long userId) {
        int position = 0;
        long waitSeconds = 0;
        Session current = session;
        if (current != null) {
            waitSeconds += Math.max(0, Duration.between(Instant.now(), current.expiresAt()).getSeconds());
        }
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i).userId().equals(userId)) {
                position = i + 1;
                waitSeconds += (long) (position - 1) * properties.getAuthorizationTimeoutSeconds();
                break;
            }
        }
        if (position == 0) {
            // Not queued anymore (the slot likely just freed); report plain authorization state.
            return new AuthorizationView(credentials.isAuthorized(userId) ? "AUTHORIZED" : "NOT_AUTHORIZED", null);
        }
        return new AuthorizationView("QUEUED", null, null, position, (int) waitSeconds);
    }

    /**
     * Reports the caller's authorization state and advances the FIFO queue: when the slot is free and
     * this user is at the front of the queue, they take over the slot and get a fresh QR code. Waiting
     * users keep their queue entry alive while they poll; abandoned entries expire like sessions.
     */
    public AuthorizationView status(Long userId) {
        RemoteWebDriver discarded;
        Session mine = null;
        AuthorizationView deferred = null;
        boolean tookOver;
        synchronized (this) {
            discarded = closeExpired();
            if (session == null && !queue.isEmpty() && queue.get(0).userId().equals(userId)) {
                queue.remove(0);
                session = new Session(userId, null, Instant.now().plusSeconds(properties.getAuthorizationTimeoutSeconds()));
                tookOver = true;
            } else {
                tookOver = false;
            }
            Session current = session;
            if (current == null || !current.userId().equals(userId)) {
                if (contains(userId)) {
                    refreshQueueEntry(userId);
                    deferred = queuedView(userId);
                } else {
                    deferred = new AuthorizationView(credentials.isAuthorized(userId) ? "AUTHORIZED" : "NOT_AUTHORIZED", null);
                }
            } else {
                mine = current;
            }
        }
        dispose(discarded);
        if (deferred != null) return deferred;
        if (mine.driver() == null) {
            // A null driver means either our own start() is still preparing, or we just took over here.
            if (!tookOver) {
                return new AuthorizationView("PENDING", null);
            }
            return startTakenOverSession(userId, mine.expiresAt());
        }
        // The UserToken cookie is the authoritative success signal; CSDN only sets it after a scan.
        LoginHarvest harvest = harvestLogin(mine.driver());
        if (harvest != null) {
            return authorizeAndClose(userId, mine, harvest);
        }
        // Not logged in yet. While any passport login frame is shown, keep the QR fresh.
        boolean loginFrameVisible = mine.driver().findElements(By.cssSelector("iframe[src*='passport.csdn.net']"))
                .stream().anyMatch(element -> {
                    try { return element.isDisplayed(); } catch (Exception ignored) { return false; }
                });
        if (loginFrameVisible) {
            // A .fail-mark placeholder means the current QR can no longer be scanned; reload so CSDN mints a fresh one.
            if (isQrExpired(mine.driver())) {
                try {
                    return new AuthorizationView("PENDING", waitForQrCode(mine.driver()));
                } catch (Exception ignored) {
                    // CSDN may need another poll cycle to serve a fresh QR; keep the modal open.
                    return new AuthorizationView("PENDING", null);
                }
            }
            return new AuthorizationView("PENDING", null);
        }
        // No cookie and no login frame: the page may still be redirecting after a scan; reload once so the
        // freshly set UserToken becomes visible.
        mine.driver().navigate().refresh();
        new WebDriverWait(mine.driver(), Duration.ofSeconds(20)).until(driver ->
                "complete".equals(((org.openqa.selenium.JavascriptExecutor) driver).executeScript("return document.readyState")));
        LoginHarvest afterReload = harvestLogin(mine.driver());
        if (afterReload == null) return new AuthorizationView("PENDING", null);
        return authorizeAndClose(userId, mine, afterReload);
    }

    /** Reads the UserToken cookie and, when present, builds the header and username to persist. */
    private LoginHarvest harvestLogin(RemoteWebDriver driver) {
        Set<Cookie> cookies = driver.manage().getCookies();
        String token = cookies.stream().filter(cookie -> "UserToken".equals(cookie.getName()))
                .map(Cookie::getValue).findFirst().orElse(null);
        if (token == null || token.isBlank()) return null;
        String header = cookies.stream().sorted(Comparator.comparing(Cookie::getName))
                .map(cookie -> cookie.getName() + "=" + cookie.getValue()).collect(Collectors.joining("; "));
        String username = cookies.stream().filter(cookie -> "UserName".equals(cookie.getName()))
                .map(Cookie::getValue).findFirst().orElse(null);
        return new LoginHarvest(header, username);
    }

    private AuthorizationView authorizeAndClose(Long userId, Session owned, LoginHarvest harvest) {
        credentials.save(userId, harvest.header(), harvest.username());
        closeOwnedSession(userId, owned);
        return new AuthorizationView("AUTHORIZED", null);
    }

    /**
     * Establishes the Selenium driver for a user who just took over the slot via {@link #status(Long)}.
     * Any failure re-queues them at the front so the next poll retries the takeover.
     */
    private AuthorizationView startTakenOverSession(Long userId, Instant expiry) {
        RemoteWebDriver driver;
        try {
            driver = newDriver();
        } catch (Exception e) {
            synchronized (this) {
                if (session != null && session.userId().equals(userId) && session.driver() == null) session = null;
            }
            return requeueAtFront(userId);
        }
        RemoteWebDriver orphan;
        synchronized (this) {
            if (session == null || !session.userId().equals(userId) || session.driver() != null) {
                orphan = driver;
            } else {
                orphan = null;
                session = new Session(userId, driver, expiry);
            }
        }
        if (orphan != null) {
            dispose(orphan);
            return requeueAtFront(userId);
        }
        try {
            return new AuthorizationView("PENDING", waitForQrCode(driver), remainingSeconds());
        } catch (Exception e) {
            releasePendingSession(userId, driver);
            return requeueAtFront(userId);
        }
    }

    /** Puts the user back at the front of the queue so a later poll can retry the takeover. */
    private AuthorizationView requeueAtFront(Long userId) {
        removeFromQueue(userId);
        queue.add(0, new Queued(userId, Instant.now().plusSeconds(properties.getAuthorizationTimeoutSeconds())));
        return queuedView(userId);
    }

    private void removeFromQueue(Long userId) {
        queue.removeIf(queued -> queued.userId().equals(userId));
    }

    private boolean contains(Long userId) {
        for (Queued queued : queue) {
            if (queued.userId().equals(userId)) return true;
        }
        return false;
    }

    /** Keeps an actively polling queued user from being evicted while they wait for a busy slot. */
    private void refreshQueueEntry(Long userId) {
        Instant refreshed = Instant.now().plusSeconds(properties.getAuthorizationTimeoutSeconds());
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i).userId().equals(userId)) {
                queue.set(i, new Queued(userId, refreshed));
                return;
            }
        }
    }

    public void disconnect(Long userId) {
        synchronized (this) {
            removeFromQueue(userId);
        }
        closeSessionFor(userId);
        credentials.delete(userId);
    }

    /** Closes the pending authorization session and leaves the queue without deleting stored credentials. */
    public void cancel(Long userId) {
        synchronized (this) {
            removeFromQueue(userId);
        }
        closeSessionFor(userId);
    }

    private void closeOwnedSession(Long userId, Session owned) {
        synchronized (this) {
            if (session != null && session.userId().equals(userId) && session.driver() == owned.driver()) session = null;
        }
        dispose(owned.driver());
    }

    private void closeSessionFor(Long userId) {
        RemoteWebDriver discarded;
        Session gone = null;
        synchronized (this) {
            discarded = closeExpired();
            if (session != null && session.userId().equals(userId)) {
                gone = session;
                session = null;
            }
        }
        dispose(discarded);
        if (gone != null) dispose(gone.driver());
    }

    /**
     * Drops expired sessions and abandoned queue entries; called only while holding {@code this}.
     * Returns the expired session's driver so the caller disposes it after releasing the lock, keeping
     * Selenium calls (driver.quit) outside the shared monitor.
     */
    private RemoteWebDriver closeExpired() {
        RemoteWebDriver expired = null;
        if (session != null && Instant.now().isAfter(session.expiresAt())) {
            expired = session.driver();
            session = null;
        }
        Instant now = Instant.now();
        queue.removeIf(queued -> now.isAfter(queued.expiresAt()));
        return expired;
    }

    /**
     * Background sweep that disposes expired sessions and abandoned queue entries even when no user
     * polls, so a headless Chrome left behind by an abandoned authorization is never leaked forever.
     */
    @Scheduled(fixedDelayString = "${mcp.csdn.platform.cleanup-interval-ms:15000}")
    public void cleanupExpiredSessions() {
        RemoteWebDriver discarded;
        synchronized (this) {
            discarded = closeExpired();
        }
        dispose(discarded);
    }

    protected RemoteWebDriver newDriver() throws Exception {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new", "--no-sandbox", "--disable-dev-shm-usage", "--window-size=1360,1020");
        return new RemoteWebDriver(URI.create(properties.getSeleniumUrl()).toURL(), options);
    }

    private void releasePendingSession(Long userId, RemoteWebDriver driver) {
        boolean removed;
        synchronized (this) {
            removed = session != null && session.userId().equals(userId) && session.driver() == driver;
            if (removed) session = null;
        }
        if (removed) dispose(driver);
    }

    private void dispose(RemoteWebDriver driver) {
        if (driver == null) return;
        try { driver.quit(); } catch (Exception ignored) { }
    }

    private record Session(Long userId, RemoteWebDriver driver, Instant expiresAt) { }
    private record Queued(Long userId, Instant expiresAt) { }
    private record LoginHarvest(String header, String username) { }
    public record AuthorizationView(String status, String qrCodeDataUrl, Integer expiresInSeconds,
                                    Integer queuePosition, Integer estimatedWaitSeconds) {
        public AuthorizationView(String status, String qrCodeDataUrl) { this(status, qrCodeDataUrl, null, null, null); }
        public AuthorizationView(String status, String qrCodeDataUrl, Integer expiresInSeconds) { this(status, qrCodeDataUrl, expiresInSeconds, null, null); }
    }
}
