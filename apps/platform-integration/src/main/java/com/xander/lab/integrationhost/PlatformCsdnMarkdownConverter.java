package com.xander.lab.integration.host;

import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;

import java.util.List;

/** Converts Content-owned Markdown into the HTML accepted by CSDN's editor API. */
final class PlatformCsdnMarkdownConverter {
    private static final Parser PARSER;
    private static final HtmlRenderer RENDERER;

    static {
        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS, List.of(TablesExtension.create()));
        PARSER = Parser.builder(options).build();
        RENDERER = HtmlRenderer.builder(options).build();
    }

    private PlatformCsdnMarkdownConverter() {
    }

    static String toHtml(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        return RENDERER.render(PARSER.parse(markdown));
    }
}
