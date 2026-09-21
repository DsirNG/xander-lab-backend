package com.xander.lab.integration.host;

import com.fasterxml.jackson.databind.JsonNode;
import org.openqa.selenium.By;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

/** Platform-owned Juejin QR authorization workflow with one bounded Selenium slot. */
@Service
@ConditionalOnProperty(prefix = "platform-integration", name = "authorization-enabled",
        havingValue = "true")
public class PlatformJuejinAuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(PlatformJuejinAuthorizationService.class);
    private static final String EDITOR_URL = "https://juejin.cn/editor/drafts/new?v=2";
    private static final Set<String> SESSION_COOKIES = Set.of("sessionid", "sessionid_ss");

    private final PlatformJuejinProperties properties;
    private final PlatformJuejinCredentialStore credentials;
    private final PlatformJuejinClient client;

    /** Session and queue mutations are guarded by this service monitor; browser calls run outside it. */
    private volatile Session session;
    private final List<Queued> queue = new ArrayList<>();

    public PlatformJuejinAuthorizationService(PlatformJuejinProperties properties,
                                               PlatformJuejinCredentialStore credentials,
                                               PlatformJuejinClient client) {
        this.properties = properties;
        this.credentials = credentials;
        this.client = client;
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
                        throw new IllegalStateException("Juejin authorization is already starting");
                    }
                    throw new IllegalStateException("Juejin authorization is already active");
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
            throw new IllegalStateException("Unable to start Juejin authorization", exception);
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
            throw new IllegalStateException("Juejin authorization was cancelled while starting");
        }
        try {
            return new AuthorizationView("PENDING", waitForQrCode(driver), remainingSeconds());
        } catch (Exception exception) {
            releasePendingSession(userId, driver);
            throw new IllegalStateException("Unable to start Juejin authorization", exception);
        }
    }

    /** Returns state, advances the queue, and persists a completed QR login. */
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
        if (harvest == null) {
            if (isQrVisible(mine.driver())) {
                return new AuthorizationView("PENDING", null);
            }
            reloadForQr(mine.driver());
            LoginHarvest afterReload = harvestLogin(mine.driver());
            if (afterReload == null) {
                return new AuthorizationView("PENDING", null);
            }
            harvest = afterReload;
        }
        PlatformJuejinClient.Credential credential = new PlatformJuejinClient.Credential(
                harvest.cookieHeader(), harvest.csrfToken(), properties.getDefaultAid(), harvest.uuid(),
                null, null, null, null, null, harvest.sessionKey());
        try {
            credential = enrichWithProfile(credential);
        } catch (Exception exception) {
            log.warn("[Juejin] user {} logged in but profile enrichment failed; keeping cookie", userId);
        }
        credentials.save(userId, credential, credential.username());
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

    /** Cancels the session and removes the user's persisted Juejin credential. */
    public void disconnect(Long userId) {
        cancel(userId);
        credentials.delete(userId);
    }

    /** Disposes abandoned browser sessions even when the caller stops polling. */
    @Scheduled(fixedDelayString = "${platform-integration.juejin.cleanup-interval-ms:15000}")
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

    /** Test seam: waits for the configured Juejin QR image. */
    protected String waitForQrCode(RemoteWebDriver driver) {
        driver.get(EDITOR_URL);
        return new WebDriverWait(driver, Duration.ofSeconds(20)).until(d -> {
            for (WebElement image : d.findElements(By.cssSelector(properties.getQrImageSelector()))) {
                String source = image.getAttribute("src");
                if (displayed(image) && source != null && source.startsWith("data:image/")) {
                    return source;
                }
            }
            return null;
        });
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

    private PlatformJuejinClient.Credential enrichWithProfile(PlatformJuejinClient.Credential base) {
        JsonNode response = client.fetchUserProfile(base);
        JsonNode data = response.path("data");
        if (data.isMissingNode() || data.isNull()) {
            return base;
        }
        return new PlatformJuejinClient.Credential(base.cookie(), base.csrfToken(), base.aid(), base.uuid(),
                textOrNull(data, "user_id"), textOrNull(data, "sec_user_id"), textOrNull(data, "user_name"),
                textOrNull(data, "avatar_large"), textOrNull(data, "phone"), base.sessionKey());
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private LoginHarvest harvestLogin(RemoteWebDriver driver) {
        Set<Cookie> cookies = driver.manage().getCookies();
        boolean loggedIn = cookies.stream().anyMatch(cookie -> SESSION_COOKIES.contains(cookie.getName()));
        if (!loggedIn) {
            return null;
        }
        String header = cookies.stream().sorted(Comparator.comparing(Cookie::getName))
                .map(cookie -> cookie.getName() + "=" + cookie.getValue())
                .collect(Collectors.joining("; "));
        return new LoginHarvest(header, cookieValue(cookies, "passport_csrf_token"),
                cookieValue(cookies, "uuid"), firstPresent(cookies,
                "sessionid_ss", "sessionid", "sid_tt"));
    }

    private boolean isQrVisible(RemoteWebDriver driver) {
        try {
            return driver.findElements(By.cssSelector(properties.getQrImageSelector()))
                    .stream().anyMatch(this::displayed);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void reloadForQr(RemoteWebDriver driver) {
        try {
            waitForQrCode(driver);
        } catch (Exception ignored) {
            // The provider may need another polling cycle to mint a fresh QR.
        }
    }

    private String cookieValue(Set<Cookie> cookies, String name) {
        return cookies.stream().filter(cookie -> name.equals(cookie.getName()))
                .map(Cookie::getValue).findFirst().orElse(null);
    }

    private String firstPresent(Set<Cookie> cookies, String... names) {
        for (String name : names) {
            String value = cookieValue(cookies, name);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
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
            // Browser cleanup is best effort after durable session state is released.
        }
    }

    private record Session(Long userId, RemoteWebDriver driver, Instant expiresAt) {
    }

    private record Queued(Long userId, Instant expiresAt) {
    }

    private record LoginHarvest(String cookieHeader, String csrfToken, String uuid, String sessionKey) {
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
