package com.xander.lab.integration.host;

import org.openqa.selenium.By;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

/** Platform-owned CSDN QR authorization workflow with one bounded Selenium slot. */
@Service
@ConditionalOnProperty(prefix = "platform-integration", name = "authorization-enabled",
        havingValue = "true")
public class PlatformCsdnAuthorizationService {

    private static final String CSDN_HOME = "https://mp.csdn.net/";
    private static final String USER_TOKEN = "UserToken";
    private final PlatformCsdnProperties properties;
    private final PlatformCsdnCredentialStore credentials;

    /** Session and queue mutations are guarded by this service monitor; Selenium runs outside it. */
    private volatile Session session;
    private final List<Queued> queue = new ArrayList<>();

    public PlatformCsdnAuthorizationService(PlatformCsdnProperties properties,
                                             PlatformCsdnCredentialStore credentials) {
        this.properties = properties;
        this.credentials = credentials;
    }

    /** Claims the single QR slot or appends the user to the FIFO queue. */
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
                expiry = Instant.now().plusSeconds(timeoutSeconds());
                session = new Session(userId, null, expiry);
            }
        }
        dispose(discarded);
        if (busyView != null) {
            return busyView;
        }

        RemoteWebDriver driver;
        try {
            driver = newDriver();
        } catch (Exception exception) {
            synchronized (this) {
                if (isPendingOwner(userId)) {
                    session = null;
                }
            }
            throw new IllegalStateException("Unable to start CSDN authorization", exception);
        }
        RemoteWebDriver orphan;
        synchronized (this) {
            if (!isPendingOwner(userId)) {
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
        } catch (Exception exception) {
            releasePendingSession(userId, driver);
            throw new IllegalStateException("Unable to start CSDN authorization", exception);
        }
    }

    /** Returns state, advances the queue, and harvests a completed QR login. */
    public AuthorizationView status(Long userId) {
        RemoteWebDriver discarded;
        Session mine = null;
        AuthorizationView deferred = null;
        boolean readAuthorizationState = false;
        boolean tookOver;
        synchronized (this) {
            discarded = closeExpired();
            if (session == null && !queue.isEmpty() && queue.get(0).userId().equals(userId)) {
                queue.remove(0);
                session = new Session(userId, null,
                        Instant.now().plusSeconds(timeoutSeconds()));
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
                    // Read the database after releasing the monitor; status polling must not
                    // serialize other users behind a slow credential store.
                    readAuthorizationState = true;
                }
            } else {
                mine = current;
            }
        }
        dispose(discarded);
        if (deferred != null) {
            return deferred;
        }
        if (readAuthorizationState) {
            return authorizationState(userId);
        }
        if (mine.driver() == null) {
            if (!tookOver) {
                return new AuthorizationView("PENDING", null);
            }
            return startTakenOverSession(userId, mine.expiresAt());
        }

        LoginHarvest harvest = harvestLogin(mine.driver());
        if (harvest != null) {
            credentials.save(userId, harvest.header(), harvest.username());
            closeOwnedSession(userId, mine);
            return new AuthorizationView("AUTHORIZED", null);
        }
        boolean loginFrameVisible = mine.driver().findElements(
                        By.cssSelector("iframe[src*='passport.csdn.net']"))
                .stream().anyMatch(this::displayed);
        if (loginFrameVisible) {
            if (isQrExpired(mine.driver())) {
                try {
                    return new AuthorizationView("PENDING", waitForQrCode(mine.driver()));
                } catch (Exception ignored) {
                    return new AuthorizationView("PENDING", null);
                }
            }
            return new AuthorizationView("PENDING", null);
        }
        mine.driver().navigate().refresh();
        new WebDriverWait(mine.driver(), Duration.ofSeconds(20)).until(driver ->
                "complete".equals(((org.openqa.selenium.JavascriptExecutor) driver)
                        .executeScript("return document.readyState")));
        LoginHarvest afterReload = harvestLogin(mine.driver());
        if (afterReload == null) {
            return new AuthorizationView("PENDING", null);
        }
        credentials.save(userId, afterReload.header(), afterReload.username());
        closeOwnedSession(userId, mine);
        return new AuthorizationView("AUTHORIZED", null);
    }

    /** Cancels the active QR session but preserves a previously stored credential. */
    public void cancel(Long userId) {
        synchronized (this) {
            removeFromQueue(userId);
        }
        closeSessionFor(userId);
    }

    /** Cancels the session and removes the user's persisted CSDN credential. */
    public void disconnect(Long userId) {
        cancel(userId);
        credentials.delete(userId);
    }

    /** Disposes abandoned browser sessions even when the caller stops polling. */
    @Scheduled(fixedDelayString = "${platform-integration.csdn.cleanup-interval-ms:15000}")
    public void cleanupExpiredSessions() {
        RemoteWebDriver discarded;
        synchronized (this) {
            discarded = closeExpired();
        }
        dispose(discarded);
    }

    /** Test seam: production opens a remote headless Chrome session. */
    protected RemoteWebDriver newDriver() throws Exception {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new", "--no-sandbox", "--disable-dev-shm-usage",
                "--window-size=1360,1020");
        return new RemoteWebDriver(URI.create(properties.getSeleniumUrl()).toURL(), options);
    }

    /** Test seam: waits for the CSDN QR image inside the passport iframe. */
    protected String waitForQrCode(RemoteWebDriver driver) {
        driver.get(CSDN_HOME);
        WebElement frame = new WebDriverWait(driver, Duration.ofSeconds(20))
                .until(d -> d.findElement(By.cssSelector("iframe[src*='passport.csdn.net']")));
        driver.switchTo().frame(frame);
        String qrCodeDataUrl = new WebDriverWait(driver, Duration.ofSeconds(20)).until(d -> {
            for (WebElement image : d.findElements(
                    By.cssSelector(".public-code img[src^='data:image/']"))) {
                String source = image.getAttribute("src");
                if (displayed(image) && source != null && source.startsWith("data:image/")) {
                    return source;
                }
            }
            return null;
        });
        driver.switchTo().defaultContent();
        return qrCodeDataUrl;
    }

    /** Returns whether the current QR has become the provider's expired placeholder. */
    protected boolean isQrExpired(RemoteWebDriver driver) {
        try {
            WebElement frame = new WebDriverWait(driver, Duration.ofSeconds(5))
                    .until(d -> d.findElement(By.cssSelector("iframe[src*='passport.csdn.net']")));
            driver.switchTo().frame(frame);
            boolean expired = driver.findElements(By.cssSelector("span.fail-mark, .fail-mark"))
                    .stream().anyMatch(this::displayed);
            driver.switchTo().defaultContent();
            return expired;
        } catch (Exception ignored) {
            return false;
        }
    }

    private AuthorizationView authorizationState(Long userId) {
        return switch (credentials.status(userId)) {
            case AUTHORIZED -> new AuthorizationView("AUTHORIZED", null);
            case EXPIRED -> new AuthorizationView("EXPIRED", null);
            case NOT_AUTHORIZED -> new AuthorizationView("NOT_AUTHORIZED", null);
        };
    }

    private AuthorizationView enqueue(Long userId) {
        removeFromQueue(userId);
        queue.add(new Queued(userId, Instant.now().plusSeconds(timeoutSeconds())));
        return queuedView(userId);
    }

    private AuthorizationView queuedView(Long userId) {
        int position = 0;
        long waitSeconds = 0;
        Session current = session;
        if (current != null) {
            waitSeconds += Math.max(0,
                    Duration.between(Instant.now(), current.expiresAt()).getSeconds());
        }
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i).userId().equals(userId)) {
                position = i + 1;
                waitSeconds += (long) (position - 1) * timeoutSeconds();
                break;
            }
        }
        if (position == 0) {
            return new AuthorizationView("NOT_AUTHORIZED", null);
        }
        return new AuthorizationView("QUEUED", null, null, position, (int) waitSeconds);
    }

    private AuthorizationView startTakenOverSession(Long userId, Instant expiry) {
        RemoteWebDriver driver;
        try {
            driver = newDriver();
        } catch (Exception ignored) {
            synchronized (this) {
                if (isPendingOwner(userId)) {
                    session = null;
                }
            }
            return requeueAtFront(userId);
        }
        RemoteWebDriver orphan;
        synchronized (this) {
            if (!isPendingOwner(userId)) {
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
        } catch (Exception ignored) {
            releasePendingSession(userId, driver);
            return requeueAtFront(userId);
        }
    }

    private LoginHarvest harvestLogin(RemoteWebDriver driver) {
        Set<Cookie> cookies = driver.manage().getCookies();
        String token = cookies.stream().filter(cookie -> USER_TOKEN.equals(cookie.getName()))
                .map(Cookie::getValue).findFirst().orElse(null);
        if (token == null || token.isBlank()) {
            return null;
        }
        String header = cookies.stream().sorted(Comparator.comparing(Cookie::getName))
                .map(cookie -> cookie.getName() + "=" + cookie.getValue())
                .collect(Collectors.joining("; "));
        String username = cookies.stream().filter(cookie -> "UserName".equals(cookie.getName()))
                .map(Cookie::getValue).findFirst().orElse(null);
        return new LoginHarvest(header, username);
    }

    private void closeOwnedSession(Long userId, Session owned) {
        synchronized (this) {
            if (session != null && session.userId().equals(userId)
                    && session.driver() == owned.driver()) {
                session = null;
            }
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
        if (gone != null) {
            dispose(gone.driver());
        }
    }

    /** Must be called while holding this service monitor. */
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

    private AuthorizationView requeueAtFront(Long userId) {
        synchronized (this) {
            removeFromQueue(userId);
            queue.add(0, new Queued(userId, Instant.now().plusSeconds(timeoutSeconds())));
            return queuedView(userId);
        }
    }

    private void releasePendingSession(Long userId, RemoteWebDriver driver) {
        boolean removed;
        synchronized (this) {
            removed = session != null && session.userId().equals(userId)
                    && session.driver() == driver;
            if (removed) {
                session = null;
            }
        }
        if (removed) {
            dispose(driver);
        }
    }

    private boolean isPendingOwner(Long userId) {
        return session != null && session.userId().equals(userId) && session.driver() == null;
    }

    private Integer remainingSeconds() {
        Session current = session;
        return current == null ? null
                : (int) Math.max(1, Duration.between(Instant.now(), current.expiresAt()).getSeconds());
    }

    private int timeoutSeconds() {
        return Math.max(1, properties.getAuthorizationTimeoutSeconds());
    }

    private void refreshQueueEntry(Long userId) {
        Instant refreshed = Instant.now().plusSeconds(timeoutSeconds());
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i).userId().equals(userId)) {
                queue.set(i, new Queued(userId, refreshed));
                return;
            }
        }
    }

    private void removeFromQueue(Long userId) {
        queue.removeIf(queued -> queued.userId().equals(userId));
    }

    private boolean contains(Long userId) {
        return queue.stream().anyMatch(queued -> queued.userId().equals(userId));
    }

    private boolean displayed(WebElement element) {
        try {
            return element.isDisplayed();
        } catch (Exception ignored) {
            return false;
        }
    }

    private void dispose(RemoteWebDriver driver) {
        if (driver == null) {
            return;
        }
        try {
            driver.quit();
        } catch (Exception ignored) {
            // Browser cleanup is best effort after the durable session state is released.
        }
    }

    private record Session(Long userId, RemoteWebDriver driver, Instant expiresAt) {
    }

    private record Queued(Long userId, Instant expiresAt) {
    }

    private record LoginHarvest(String header, String username) {
    }

    /** Stable response shape shared by the Platform public controller and Gateway contract. */
    public record AuthorizationView(String status, String qrCodeDataUrl, Integer expiresInSeconds,
                                    Integer queuePosition, Integer estimatedWaitSeconds) {
        public AuthorizationView(String status, String qrCodeDataUrl) {
            this(status, qrCodeDataUrl, null, null, null);
        }

        public AuthorizationView(String status, String qrCodeDataUrl, Integer expiresInSeconds) {
            this(status, qrCodeDataUrl, expiresInSeconds, null, null);
        }
    }
}
