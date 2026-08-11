package com.xander.lab.bloghtml.api;

/**
 * Result of creating a preview session.
 *
 * @param id       session id, embedded in the page URL and echoed by the height
 *                 reporter messages so the host can correlate them
 * @param pageUrl  URL of the sandbox page; the host iframes this URL
 */
public record BlogHtmlPreviewVO(String id, String pageUrl) {
}