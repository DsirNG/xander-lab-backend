package com.xander.lab.bloghtml.application;

import com.xander.lab.bloghtml.infrastructure.BlogHtmlPreviewProperties;
import com.xander.lab.bloghtml.infrastructure.BlogHtmlPreviewStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BlogHtmlPreviewServiceTest {

    private static final String IP = "203.0.113.7";

    private final BlogHtmlPreviewProperties properties = new BlogHtmlPreviewProperties();
    private final BlogHtmlPreviewStore store = mock(BlogHtmlPreviewStore.class);
    private final PreviewPageBuilder builder = new PreviewPageBuilder();

    private BlogHtmlPreviewService service;

    @BeforeEach
    void setUp() {
        properties.setPageUrlPattern("http://localhost:30002/blog-html/%s/raw");
        when(store.rateCount(anyString(), anyInt())).thenReturn(1L);
        service = new BlogHtmlPreviewService(properties, store, builder);
    }

    @Test
    void createGeneratesHexIdAndFormattedPageUrl() {
        var session = service.create("<p>hi</p>", IP);

        assertThat(session.id()).matches("[0-9a-f]{16}");
        assertThat(session.pageUrl()).isEqualTo("http://localhost:30002/blog-html/" + session.id() + "/raw");
        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(store).save(eq(session.id()), codeCaptor.capture(), eq(properties.getTtlSeconds()));
        assertThat(codeCaptor.getValue()).isEqualTo("<p>hi</p>");
    }

    @Test
    void createRejectsBlankCode() {
        assertThatThrownBy(() -> service.create("   ", IP))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(400));
    }

    @Test
    void createRejectsOversizedCode() {
        String big = "x".repeat(properties.getMaxCodeBytes() + 1);
        assertThatThrownBy(() -> service.create(big, IP))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(413));
    }

    @Test
    void createRateLimitsByIp() {
        when(store.rateCount(anyString(), anyInt())).thenReturn(11L);

        assertThatThrownBy(() -> service.create("<p>hi</p>", IP))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(429));
        verify(store).rateCount("blog-html:rate:" + IP, properties.getRateLimitWindowSeconds());
    }

    @Test
    void renderPageBuildsStandaloneDocument() {
        when(store.load("abcdef0123456789")).thenReturn("<html><body>hi</body></html>");

        String page = service.renderPage("abcdef0123456789");

        assertThat(page).contains("Content-Security-Policy").contains("<body>hi</body>");
    }

    @Test
    void renderPageRejectsMalformedSessionId() {
        assertThatThrownBy(() -> service.renderPage("../evil"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(400));
    }

    @Test
    void renderPageReturns404WhenSessionExpired() {
        when(store.load("abcdef0123456789")).thenReturn(null);

        assertThatThrownBy(() -> service.renderPage("abcdef0123456789"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(404));
    }

    @Test
    void frameAncestorsCspExposesConfiguredValue() {
        properties.setFrameAncestors("https://dinqor.cn");
        assertThat(service.frameAncestorsCsp()).isEqualTo("https://dinqor.cn");
    }
}
