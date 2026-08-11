package com.xander.lab.bloghtml.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PreviewPageBuilderTest {

    private final PreviewPageBuilder builder = new PreviewPageBuilder();

    @Test
    void fragmentIsWrappedInSkeletonWithCspAndReporter() {
        String page = builder.buildPage("<p>hello &amp; bye</p>");

        assertThat(page).contains("<!doctype html>");
        assertThat(page).contains("<body>");
        assertThat(page).contains("<p>hello &amp; bye</p>");
        assertThat(page).contains("Content-Security-Policy");
        assertThat(page).contains("default-src 'none'");
        assertThat(page).contains("__sandboxHeight");
        assertThat(page).contains("MutationObserver");
    }

    @Test
    void fullDocumentGetsInjectionBeforeClosingHead() {
        String page = builder.buildPage("<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\">\n</head>\n<body>hi</body>\n</html>");

        int cspIndex = page.indexOf("Content-Security-Policy");
        int headEndIndex = page.indexOf("</head>");
        int metaIndex = page.indexOf("<meta charset=\"utf-8\">");
        assertThat(cspIndex).isGreaterThan(-1);
        assertThat(headEndIndex).isGreaterThan(cspIndex);
        assertThat(metaIndex).isGreaterThan(-1).isLessThan(cspIndex);
        assertThat(page).contains("<body>hi</body>");
        assertThat(page).containsOnlyOnce("</head>");
    }

    @Test
    void openingHeadWithoutClosingHeadGetsInjectedAndClosed() {
        String page = builder.buildPage("<html><head><style>body{}</style><body>x</body></html>");

        int headStart = page.indexOf("<head>");
        int cspIndex = page.indexOf("Content-Security-Policy");
        int headClose = page.indexOf("</head>");
        assertThat(cspIndex).isGreaterThan(headStart);
        assertThat(headClose).isGreaterThan(cspIndex);
        assertThat(page).contains("<body>x</body>").contains("</html>");
    }

    @Test
    void htmlWithoutHeadGetsSyntheticHeadInserted() {
        String page = builder.buildPage("<html><body>hi</body></html>");

        assertThat(page).contains("<html>\n<head>");
        assertThat(page).contains("</head><body>hi</body>");
        assertThat(page).containsOnlyOnce("Content-Security-Policy");
    }

    @Test
    void bareBodyGetsInjectionPrepended() {
        String page = builder.buildPage("<body>hi</body>");

        assertThat(page.indexOf("Content-Security-Policy")).isLessThan(page.indexOf("<body>"));
        assertThat(page).contains("<body>hi</body>");
    }

    @Test
    void heightReporterIsPresentOnce() {
        String page = builder.buildPage("<p>x</p>");
        assertThat(page).contains("__sandboxHeight");
    }

    @Test
    void nullCodeRendersEmptyBody() {
        String page = builder.buildPage(null);
        assertThat(page).contains("<body>\n\n</body>").contains("</html>");
    }
}