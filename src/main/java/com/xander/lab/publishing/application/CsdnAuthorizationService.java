package com.xander.lab.publishing.application;

import com.xander.lab.publishing.infrastructure.CsdnProperties;
import lombok.RequiredArgsConstructor;
import org.openqa.selenium.By;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.WebDriver;
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
    private Session session;

    public synchronized AuthorizationView start(Long userId) {
        closeExpired();
        if (session != null && !session.userId().equals(userId)) {
            throw new IllegalStateException("Another CSDN authorization is in progress");
        }
        closeSession();
        try {
            ChromeOptions options = new ChromeOptions();
            options.addArguments("--headless=new", "--no-sandbox", "--disable-dev-shm-usage", "--window-size=1360,1020");
            WebDriver driver = new RemoteWebDriver(URI.create(properties.getSeleniumUrl()).toURL(), options);
            session = new Session(userId, driver, Instant.now().plusSeconds(properties.getAuthorizationTimeoutSeconds()));
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
            return new AuthorizationView("PENDING", qrCodeDataUrl);
        } catch (Exception e) {
            closeSession();
            throw new IllegalStateException("Unable to start CSDN authorization", e);
        }
    }

    public synchronized AuthorizationView status(Long userId) {
        closeExpired();
        if (session == null || !session.userId().equals(userId)) {
            return new AuthorizationView(credentials.isAuthorized(userId) ? "AUTHORIZED" : "NOT_AUTHORIZED", null);
        }
        Set<Cookie> cookies = session.driver().manage().getCookies();
        boolean loginFrameVisible = session.driver().findElements(By.cssSelector("iframe[src*='passport.csdn.net/account/login']"))
                .stream().anyMatch(element -> {
                    try { return element.isDisplayed(); } catch (Exception ignored) { return false; }
                });
        boolean loggedIn = !loginFrameVisible && cookies.stream().anyMatch(c -> "UserToken".equals(c.getName()));
        if (!loggedIn) return new AuthorizationView("PENDING", null);
        String header = cookies.stream().sorted(Comparator.comparing(Cookie::getName))
                .map(c -> c.getName() + "=" + c.getValue()).collect(Collectors.joining("; "));
        String username = cookies.stream().filter(c -> "UserName".equals(c.getName())).map(Cookie::getValue).findFirst().orElse(null);
        credentials.save(userId, header, username);
        closeSession();
        return new AuthorizationView("AUTHORIZED", null);
    }

    public synchronized void disconnect(Long userId) {
        if (session != null && session.userId().equals(userId)) closeSession();
        credentials.delete(userId);
    }

    private void closeExpired() {
        if (session != null && Instant.now().isAfter(session.expiresAt())) closeSession();
    }

    private void closeSession() {
        if (session == null) return;
        try { session.driver().quit(); } catch (Exception ignored) { }
        session = null;
    }

    private record Session(Long userId, WebDriver driver, Instant expiresAt) { }
    public record AuthorizationView(String status, String qrCodeDataUrl) { }
}
