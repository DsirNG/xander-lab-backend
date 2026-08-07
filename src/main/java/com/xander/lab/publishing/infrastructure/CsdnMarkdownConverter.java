package com.xander.lab.publishing.infrastructure;

import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;

import java.util.List;

/** Converts the local Markdown article body to the HTML payload expected by CSDN. */
final class CsdnMarkdownConverter {
    private static final Parser PARSER;
    private static final HtmlRenderer RENDERER;

    static {
        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS, List.of(TablesExtension.create()));
        PARSER = Parser.builder(options).build();
        RENDERER = HtmlRenderer.builder(options).build();
    }

    private CsdnMarkdownConverter() {
    }

    static String toHtml(String markdown) {
        if (markdown == null || markdown.trim().isEmpty()) {
            return "";
        }
        return RENDERER.render(PARSER.parse(markdown));
    }
}
