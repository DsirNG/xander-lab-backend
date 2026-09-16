package com.xander.lab.bloghtml.application;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Assembles the sandbox page that hosts untrusted preview HTML.
 *
 * <p>The page runs on its own origin (independent of the blog site), which is
 * the primary isolation boundary. On top of that every output carries a strict
 * CSP meta tag cutting network access, forms and base-url tricks. A tiny
 * reporter script tells the host iframe the intrinsic content height so it can
 * size itself. Unlike srcdoc-based sandboxes, history.pushState keeps working
 * natively here, so anchor navigation demos behave exactly as on a real page.</p>
 */
@Component
public class PreviewPageBuilder {

    private static final Pattern OPENING_HTML = Pattern.compile("<html[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLOSING_HEAD = Pattern.compile("</head>", Pattern.CASE_INSENSITIVE);
    private static final Pattern OPENING_HEAD = Pattern.compile("<head[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern OPENING_BODY = Pattern.compile("<body[\\s>]", Pattern.CASE_INSENSITIVE);

    private static final String CSP_META =
            "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: blob:; base-uri 'none'; form-action 'none'\">";

    /** Keeps scrolling and anchor navigation inside the embedded preview. */
    private static final String SCROLL_CONTAINMENT_STYLE =
            "<style>html, body { overscroll-behavior: contain; }</style>";

    private static final String HEIGHT_REPORTER =
            "<script>\n"
                    + "(function () {\n"
                    + "  function announce() {\n"
                    + "    var height = document.documentElement.scrollHeight || document.body.scrollHeight || 0;\n"
                    + "    try { parent.postMessage({ type: '__sandboxHeight', height: height }, '*'); } catch (e) {}\n"
                    + "  }\n"
                    + "  window.addEventListener('load', announce);\n"
                    + "  window.addEventListener('resize', announce);\n"
                    + "  if (window.MutationObserver) {\n"
                    + "    new MutationObserver(announce).observe(\n"
                    + "      document.documentElement,\n"
                    + "      { childList: true, subtree: true, attributes: true, characterData: true }\n"
                    + "    );\n"
                    + "  }\n"
                    + "  setTimeout(announce, 300);\n"
                    + "})();\n"
                    + "</script>";

    private static final String DEBUG_REPORTER =
            "<script>\n"
                    + "(function () {\n"
                    + "  var scheduled = false;\n"
                    + "  function describe(element) {\n"
                    + "    if (!element || !element.tagName) return null;\n"
                    + "    return element.tagName.toLowerCase()\n"
                    + "      + (element.id ? '#' + element.id : '')\n"
                    + "      + (element.className && typeof element.className === 'string'\n"
                    + "        ? '.' + element.className.trim().replace(/\\s+/g, '.') : '');\n"
                    + "  }\n"
                    + "  function report(eventName, target) {\n"
                    + "    var scrollingElement = document.scrollingElement;\n"
                    + "    try { parent.postMessage({\n"
                    + "      type: '__sandboxDebug', event: eventName, hash: location.hash,\n"
                    + "      scrollY: window.scrollY, scrollTop: scrollingElement ? scrollingElement.scrollTop : null,\n"
                    + "      activeElement: describe(document.activeElement), target: describe(target),\n"
                    + "      viewportHeight: window.innerHeight, documentHeight: document.documentElement.scrollHeight\n"
                    + "    }, '*'); } catch (e) {}\n"
                    + "  }\n"
                    + "  document.addEventListener('click', function (event) {\n"
                    + "    var link = event.target && event.target.closest ? event.target.closest('a[href]') : null;\n"
                    + "    if (link) report('anchor-click:' + link.getAttribute('href'), link);\n"
                    + "  }, true);\n"
                    + "  document.addEventListener('focusin', function (event) { report('focusin', event.target); }, true);\n"
                    + "  window.addEventListener('hashchange', function () { report('hashchange'); });\n"
                    + "  window.addEventListener('popstate', function () { report('popstate'); });\n"
                    + "  window.addEventListener('scroll', function () {\n"
                    + "    if (scheduled) return;\n"
                    + "    scheduled = true;\n"
                    + "    requestAnimationFrame(function () { scheduled = false; report('scroll'); });\n"
                    + "  }, { passive: true });\n"
                    + "  window.addEventListener('load', function () { report('load'); });\n"
                    + "})();\n"
                    + "</script>";

    private static final String FRAGMENT_STYLE =
            "<style>\n"
                    + "  html, body { margin: 0; padding: 16px; background: #fff; color: #0f172a; font-family: system-ui, -apple-system, Segoe UI, sans-serif; }\n"
                    + "</style>";

    private static final String FRAGMENT_PAGE_TEMPLATE =
            "<!doctype html>\n"
                    + "<html lang=\"zh-CN\">\n"
                    + "<head>\n"
                    + "  <meta charset=\"UTF-8\">\n"
                    + "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n"
                    + CSP_META + "\n"
                    + SCROLL_CONTAINMENT_STYLE + "\n"
                    + HEIGHT_REPORTER + "\n"
                    + DEBUG_REPORTER + "\n"
                    + FRAGMENT_STYLE + "\n"
                    + "</head>\n"
                    + "<body>\n"
                    + "%s\n"
                    + "</body>\n"
                    + "</html>";

    /**
     * Builds the complete sandbox page for the given preview code.
     *
     * @param code the untrusted preview HTML (document or fragment)
     * @return a complete standalone HTML document
     */
    public String buildPage(String code) {
        String raw = code == null ? "" : code;
        if (isFullDocument(raw)) {
            return injectIntoFullDocument(raw);
        }
        return String.format(FRAGMENT_PAGE_TEMPLATE, raw);
    }

    private boolean isFullDocument(String raw) {
        return OPENING_HTML.matcher(raw).find()
                || OPENING_HEAD.matcher(raw).find()
                || OPENING_BODY.matcher(raw).find();
    }

    private String injectIntoFullDocument(String raw) {
        var closingHead = CLOSING_HEAD.matcher(raw);
        if (closingHead.find()) {
            return raw.substring(0, closingHead.start())
                    + CSP_META + "\n" + SCROLL_CONTAINMENT_STYLE + "\n" + HEIGHT_REPORTER + DEBUG_REPORTER
                    + raw.substring(closingHead.start());
        }
        var openingHead = OPENING_HEAD.matcher(raw);
        if (openingHead.find()) {
            // Unclosed <head>: inject the hardening fragment and close the tag so
            // the code that follows is parsed as body content, not head content.
            return raw.substring(0, openingHead.end())
                    + "\n" + CSP_META + SCROLL_CONTAINMENT_STYLE + HEIGHT_REPORTER + DEBUG_REPORTER + "</head>"
                    + raw.substring(openingHead.end());
        }
        var openingHtml = OPENING_HTML.matcher(raw);
        if (openingHtml.find()) {
            return raw.substring(0, openingHtml.end())
                    + "\n<head>" + CSP_META + SCROLL_CONTAINMENT_STYLE + HEIGHT_REPORTER + DEBUG_REPORTER + "</head>"
                    + raw.substring(openingHtml.end());
        }
        return CSP_META + SCROLL_CONTAINMENT_STYLE + HEIGHT_REPORTER + DEBUG_REPORTER + raw;
    }
}
