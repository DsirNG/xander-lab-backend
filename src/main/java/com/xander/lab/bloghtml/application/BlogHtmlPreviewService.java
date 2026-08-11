package com.xander.lab.bloghtml.application;

import com.xander.lab.bloghtml.infrastructure.BlogHtmlPreviewProperties;
import com.xander.lab.bloghtml.infrastructure.BlogHtmlPreviewStore;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Preview session use cases: create a short-lived session for untrusted HTML
 * and render its sandbox page. Creation is public but rate-limited per IP;
 * sessions expire via the Redis TTL and are unguessable (128-bit random ids).
 */
@Service
@RequiredArgsConstructor
public class BlogHtmlPreviewService {

    private static final String RATE_LIMIT_PREFIX = "blog-html:rate:";
    private static final Pattern SESSION_ID = Pattern.compile("[0-9a-f]{16}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final BlogHtmlPreviewProperties properties;
    private final BlogHtmlPreviewStore store;
    private final PreviewPageBuilder pageBuilder;

    /** Creates a session and returns the id plus the sandbox page URL. */
    public GeneratedSession create(String code, String clientIp) {
        checkRateLimit(clientIp);
        if (code == null || code.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "HTML 代码不能为空");
        }
        if (code.getBytes(StandardCharsets.UTF_8).length > properties.getMaxCodeBytes()) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "HTML 代码超过大小限制");
        }
        String sessionId = randomSessionId();
        store.save(sessionId, code, properties.getTtlSeconds());
        String pageUrl = String.format(properties.getPageUrlPattern(), sessionId);
        return new GeneratedSession(sessionId, pageUrl);
    }

    /** Renders the sandbox page for an existing session; 404 when expired. */
    public String renderPage(String sessionId) {
        if (sessionId == null || !SESSION_ID.matcher(sessionId).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "预览会话 ID 无效");
        }
        String code = store.load(sessionId);
        if (code == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "预览已过期或不存在");
        }
        return pageBuilder.buildPage(code);
    }

    /** The CSP {@code frame-ancestors} value configured for the sandbox page. */
    public String frameAncestorsCsp() {
        return properties.getFrameAncestors();
    }

    /** Fixed-window Redis rate limiter keyed by client IP; 429 beyond the cap. */
    private void checkRateLimit(String clientIp) {
        String ip = clientIp == null || clientIp.isBlank() ? "unknown" : clientIp;
        String key = RATE_LIMIT_PREFIX + ip;
        Long count = store.rateCount(key, properties.getRateLimitWindowSeconds());
        if (count != null && count > properties.getRateLimitMaxRequests()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁，请稍后再试");
        }
    }

    private String randomSessionId() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public record GeneratedSession(String id, String pageUrl) {
    }
}