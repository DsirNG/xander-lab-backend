package com.xander.lab.knowledgebase.infrastructure;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 把上传的文件字节抽成纯文本，供章节识别与结构图生成使用。
 *
 * <p>只处理本仓库已有依赖能处理的格式：纯文本族直接解码，HTML 走 jsoup。
 * PDF / DOC / DOCX 没有可用的解析库，明确返回「不支持」而不是抽出一堆乱码
 * ——文件照样存得下，只是没有结构图。</p>
 *
 * <p>HTML 不是简单 {@code text()} 抽平：那样会把标题层级全丢掉，章节识别就无从下手。
 * 这里把 {@code h1..h6} 还原成 Markdown 的 {@code #} 标题、{@code li} 还原成列表项，
 * 让下游 {@code ChapterDetector} 拿到和 Markdown 一致的形状。</p>
 */
@Slf4j
@Component
public class DocumentTextExtractor {

    /** 会被单独成段输出的块级标签。 */
    private static final Set<String> BLOCK_TAGS = Set.of(
            "h1", "h2", "h3", "h4", "h5", "h6", "p", "li", "pre", "blockquote", "dd", "dt", "td", "th");

    private final KnowledgeBaseProperties properties;

    public DocumentTextExtractor(KnowledgeBaseProperties properties) {
        this.properties = properties;
    }

    /**
     * @param supported 该格式能否抽取；false 时 {@code text} 为空
     * @param text      抽取出的纯文本，已按配置上限截断
     */
    public record ExtractionResult(boolean supported, String text) {

        public static ExtractionResult unsupported() {
            return new ExtractionResult(false, "");
        }
    }

    /** @param extension 小写扩展名，不含点 */
    public ExtractionResult extract(byte[] content, String extension) {
        if (content == null || content.length == 0) {
            return new ExtractionResult(true, "");
        }
        String ext = extension == null ? "" : extension.toLowerCase();
        if (!properties.getTextExtensions().contains(ext)) {
            return ExtractionResult.unsupported();
        }
        try {
            String raw = decode(content);
            String text = "html".equals(ext) || "htm".equals(ext) ? fromHtml(raw) : raw;
            return new ExtractionResult(true, truncate(normalizeNewlines(text)));
        } catch (Exception e) {
            // 抽取失败不能拖垮上传：文件已经存下了，结构图交给调用方标成 FAILED。
            log.warn("[知识库] 正文抽取失败，extension={}: {}", ext, e.getMessage());
            return ExtractionResult.unsupported();
        }
    }

    /** 优先 UTF-8；出现替换字符说明不是 UTF-8，退回 GBK 再试（中文 txt 常见）。 */
    private String decode(byte[] content) {
        String utf8 = new String(content, StandardCharsets.UTF_8);
        if (utf8.indexOf('�') < 0) {
            return stripBom(utf8);
        }
        try {
            return stripBom(new String(content, Charset.forName("GBK")));
        } catch (Exception e) {
            return stripBom(utf8);
        }
    }

    private String stripBom(String text) {
        return text.startsWith("﻿") ? text.substring(1) : text;
    }

    private String normalizeNewlines(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private String truncate(String text) {
        int limit = Math.max(1, properties.getMaxExtractChars());
        return text.length() > limit ? text.substring(0, limit) : text;
    }

    private String fromHtml(String html) {
        Document document = Jsoup.parse(html);
        StringBuilder builder = new StringBuilder();
        for (Element element : document.select(String.join(",", BLOCK_TAGS))) {
            if (hasBlockAncestor(element)) {
                continue;
            }
            String text = element.text().trim();
            if (text.isEmpty()) {
                continue;
            }
            String tag = element.tagName();
            if (tag.length() == 2 && tag.charAt(0) == 'h' && Character.isDigit(tag.charAt(1))) {
                builder.append("#".repeat(tag.charAt(1) - '0')).append(' ').append(text).append("\n\n");
            } else if ("li".equals(tag)) {
                builder.append("- ").append(text).append('\n');
            } else {
                builder.append(text).append("\n\n");
            }
        }
        return builder.isEmpty() ? document.text() : builder.toString();
    }

    /** 嵌套块级元素（{@code li} 里再套 {@code p}）只输出最外层，避免同一段文字重复两遍。 */
    private boolean hasBlockAncestor(Element element) {
        for (Element parent = element.parent(); parent != null; parent = parent.parent()) {
            if (BLOCK_TAGS.contains(parent.tagName())) {
                return true;
            }
        }
        return false;
    }
}
