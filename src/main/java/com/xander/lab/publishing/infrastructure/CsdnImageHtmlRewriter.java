package com.xander.lab.publishing.infrastructure;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Rewrites article HTML image links to CSDN-hosted URLs before publish. */
@Component
final class CsdnImageHtmlRewriter {
    private final CsdnImageStorageClient uploadClient;

    CsdnImageHtmlRewriter(CsdnImageStorageClient uploadClient) {
        this.uploadClient = uploadClient;
    }

    String rewrite(String html, String cookie, String apiKey, String articleId, String originalUrl) {
        if (html == null || html.isBlank()) {
            return "";
        }
        Document document = Jsoup.parseBodyFragment(html);
        if (originalUrl != null && !originalUrl.isBlank()) {
            Element link = new Element("p").appendElement("a")
                    .attr("href", originalUrl)
                    .attr("target", "_blank")
                    .text("原文链接");
            document.body().prependChild(link);
        }
        Elements images = document.select("img[src]");
        if (images.isEmpty()) {
            return document.body().html();
        }
        for (Element image : images) {
            String src = image.attr("src").trim();
            if (src.isBlank() || src.startsWith("data:")) {
                continue;
            }
            String nonce = UUID.randomUUID().toString();
            String timestamp = String.valueOf(System.currentTimeMillis());
            Map<String, String> signedHeaders = new LinkedHashMap<>();
            signedHeaders.put("x-ca-key", apiKey);
            signedHeaders.put("x-ca-nonce", nonce);
            signedHeaders.put("x-ca-timestamp", timestamp);
            String signature = signForImageUpload(apiKey, signedHeaders);
            String uploaded = uploadClient.upload(cookie, apiKey, nonce, signature, timestamp, src, articleId);
            image.attr("src", uploaded);
        }
        return document.body().html();
    }

    private String signForImageUpload(String apiKey, Map<String, String> signedHeaders) {
        String canonical = "POST\napplication/json, text/plain, */*\n\napplication/json;charset=UTF-8\n\n"
                + "x-ca-key:" + apiKey + "\n"
                + "x-ca-nonce:" + signedHeaders.get("x-ca-nonce") + "\n"
                + "x-ca-timestamp:" + signedHeaders.get("x-ca-timestamp") + "\n"
                + "/resource-api/v1/image/external/storage";
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    "9znpamsyl2c7cdrr9sas0le9vbc3r6ba".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    "HmacSHA256"));
            return java.util.Base64.getEncoder().encodeToString(mac.doFinal(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to sign CSDN image upload request", e);
        }
    }
}
