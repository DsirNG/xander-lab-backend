package com.xander.lab.integration.wechat.infrastructure;

import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.util.List;

/** Converts local Markdown to a conservative HTML fragment accepted by WeChat drafts. */
final class WechatMarkdownConverter {
    private static final Parser PARSER;
    private static final HtmlRenderer RENDERER;

    static {
        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS, List.of(TablesExtension.create()));
        PARSER = Parser.builder(options).build();
        RENDERER = HtmlRenderer.builder(options).build();
    }

    private WechatMarkdownConverter() {
    }

    static String toHtml(String markdown) {
        if (markdown == null || markdown.isBlank()) return "";
        Document document = Jsoup.parseBodyFragment(RENDERER.render(PARSER.parse(markdown)));
        document.select("script, iframe, object, embed, form, input, button").remove();
        document.select("*[onload], *[onclick], *[onerror], *[onmouseover]").forEach(element -> {
            element.attributes().asList().stream()
                    .filter(attribute -> attribute.getKey().toLowerCase().startsWith("on"))
                    .forEach(attribute -> element.removeAttr(attribute.getKey()));
        });
        return document.body().html();
    }
}
