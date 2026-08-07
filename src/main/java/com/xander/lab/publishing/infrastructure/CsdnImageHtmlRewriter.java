package com.xander.lab.publishing.infrastructure;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** Rewrites article HTML image links to CSDN-hosted URLs before publish. */
@Component
final class CsdnImageHtmlRewriter {
    private final CsdnImageStorageClient uploadClient;

    CsdnImageHtmlRewriter(CsdnImageStorageClient uploadClient) {
        this.uploadClient = uploadClient;
    }

    String rewrite(String html, String cookie, String apiKey, String articleId) {
        if (html == null || html.isBlank()) {
            return "";
        }
        Document document = Jsoup.parseBodyFragment(html);
        Elements images = document.select("img[src]");
        if (images.isEmpty()) {
            return document.body().html();
        }
        String timestamp = String.valueOf(System.currentTimeMillis());
        for (Element image : images) {
            String src = image.attr("src").trim();
            if (src.isBlank() || src.startsWith("data:")) {
                continue;
            }
            String nonce = UUID.randomUUID().toString();
            String signature = CsdnRequestSigner.sign(
                    "POST",
                    "application/json, text/plain, */*",
                    "application/json;charset=UTF-8",
                    List.of(
                            "x-ca-key:" + apiKey,
                            "x-ca-nonce:" + nonce,
                            "x-ca-timestamp:" + timestamp),
                    "/resource-api/v1/image/external/storage");
            String uploaded = uploadClient.upload(cookie, apiKey, nonce, signature, timestamp, src, articleId);
            image.attr("src", uploaded);
        }
        return document.body().html();
    }
}
