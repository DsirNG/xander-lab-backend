package com.xander.lab.bloghtml.infrastructure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for blog HTML preview sessions and the sandbox page.
 */
@Data
@Component
@ConfigurationProperties(prefix = "blog-html.preview")
public class BlogHtmlPreviewProperties {

    /**
     * URL pattern for the sandbox page; the single {@code %s} placeholder is
     * the session id. Production uses a per-session subdomain, e.g.
     * {@code https://%s.blogHTML.dinqor.cn/raw}; local development
     * can point at {@code http://localhost:30002/blog-html/%s/raw}.
     */
    private String pageUrlPattern = "https://%s.blogHTML.dinqor.cn/raw";

    /**
     * Space-separated origins allowed to embed the sandbox page, applied as
     * the CSP {@code frame-ancestors} directive on the sandbox response.
     * {@code frame-ancestors} is ignored in meta tags, so the header is
     * authoritative.
     */
    private String frameAncestors = "https://dinqor.cn";

    /** Seconds before a preview session expires and its code is evicted. */
    private int ttlSeconds = 1800;

    /** Maximum accepted code size in bytes; larger payloads are rejected. */
    private int maxCodeBytes = 262144;

    /** Fixed-window rate limit: max creations per IP per window. */
    private int rateLimitMaxRequests = 10;

    /** Fixed-window rate limit window in seconds. */
    private int rateLimitWindowSeconds = 60;
}