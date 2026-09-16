package com.xander.lab.bloghtml.api;

import com.xander.lab.bloghtml.application.BlogHtmlPreviewService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BlogHtmlPreviewControllerTest {

    private final BlogHtmlPreviewService service = mock(BlogHtmlPreviewService.class);
    private final BlogHtmlPreviewController controller = new BlogHtmlPreviewController(service);

    @Test
    void createPreviewReturnsSessionVo() {
        when(service.frameAncestorsCsp()).thenReturn("https://dinqor.cn");
        when(service.create("<p>hi</p>", null))
                .thenReturn(new BlogHtmlPreviewService.GeneratedSession(
                        "abcdef0123456789", "http://localhost:30002/blog-html/abcdef0123456789/raw"));
        BlogHtmlPreviewCreateRequest request = new BlogHtmlPreviewCreateRequest();
        request.setCode("<p>hi</p>");

        var result = controller.createPreview(request);

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData().id()).isEqualTo("abcdef0123456789");
        assertThat(result.getData().pageUrl())
                .isEqualTo("http://localhost:30002/blog-html/abcdef0123456789/raw");
    }

    @Test
    void renderPageAppliesHardeningHeadersAndBody() {
        when(service.renderPage("abcdef0123456789")).thenReturn("<html><body>hi</body></html>");
        when(service.frameAncestorsCsp()).thenReturn("https://dinqor.cn");

        ResponseEntity<String> response = controller.renderPage("abcdef0123456789");

        assertThat(response.getBody()).isEqualTo("<html><body>hi</body></html>");
        assertThat(response.getHeaders().getFirst("Content-Security-Policy"))
                .contains("default-src 'none'")
                .contains("frame-ancestors https://dinqor.cn");
        assertThat(response.getHeaders().getFirst("Cache-Control")).contains("no-store");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeaders().getFirst("Content-Type")).isEqualTo("text/html;charset=UTF-8");
    }
}