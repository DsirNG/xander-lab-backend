package com.xander.lab.bloghtml.api;

import com.xander.lab.bloghtml.application.BlogHtmlPreviewService;
import com.xander.lab.common.Result;
import com.xander.lab.identity.security.UserContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * Blog HTML preview endpoints. Creation is a public, rate-limited API; the
 * sandbox page route deliberately lives outside {@code /api} so iframes load
 * it without auth, CORS or cookies, and its CSP frame-ancestors restricts
 * which sites may embed it.
 */
@RestController
@RequiredArgsConstructor
public class BlogHtmlPreviewController {

    private static final String PAGE_CSP_PREFIX =
            "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; "
                    + "img-src data: blob:; base-uri 'none'; form-action 'none'; frame-ancestors ";

    private final BlogHtmlPreviewService previewService;

    /**
     * Creates a preview session. Public (no login required) and rate-limited
     * per IP; the returned page URL is safe to embed in an iframe.
     */
    @PostMapping("/api/blog-html/previews")
    public Result<BlogHtmlPreviewVO> createPreview(@Valid @RequestBody BlogHtmlPreviewCreateRequest request) {
        BlogHtmlPreviewService.GeneratedSession session = previewService.create(request.getCode(), UserContext.getClientIp());
        return Result.success(new BlogHtmlPreviewVO(session.id(), session.pageUrl()));
    }

    /**
     * Serves the sandbox page for a session. The page is a complete HTML
     * document running on its own origin; heavy anti-caching and strict CSP
     * headers are applied so embedded code cannot mix with blog credentials.
     */
    @GetMapping(value = "/blog-html/{id}/raw", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> renderPage(@PathVariable String id) {
        String page = previewService.renderPage(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_HTML_VALUE + ";charset=UTF-8")
                .header("Content-Security-Policy", PAGE_CSP_PREFIX + previewService.frameAncestorsCsp())
                .header(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue())
                .header("X-Content-Type-Options", "nosniff")
                .body(page);
    }
}