package com.xander.lab.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.config.BlogAgentProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class BlogAgentImageClient {
    private final BlogAgentProperties properties;
    private final ObjectMapper objectMapper;

    public boolean isEnabled() {
        return StringUtils.hasText(properties.getApiKey()) && StringUtils.hasText(properties.getImageModel());
    }

    public GeneratedImage generate(String prompt) {
        if (!isEnabled()) throw new IllegalStateException("博客智能体图片模型尚未配置");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", properties.getImageModel());
        payload.put("prompt", prompt);
        payload.put("size", properties.getImageSize());
        payload.put("n", 1);

        JsonNode response = RestClient.builder()
                .baseUrl(trimTrailingSlash(properties.getBaseUrl()))
                .defaultHeader("Authorization", "Bearer " + properties.getApiKey())
                .build()
                .post()
                .uri("/images/generations")
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(JsonNode.class);

        JsonNode image = response == null ? objectMapper.createObjectNode() : response.path("data").path(0);
        String base64 = image.path("b64_json").asText();
        if (StringUtils.hasText(base64)) {
            return new GeneratedImage(Base64.getDecoder().decode(base64), "image/png", "png");
        }
        String url = image.path("url").asText();
        if (!StringUtils.hasText(url)) throw new IllegalStateException("图片模型没有返回可读取的图片");
        return download(url);
    }

    private GeneratedImage download(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(3))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build()
                    .send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300 || response.body().length == 0) {
                throw new IllegalStateException("下载生成图片失败（HTTP " + response.statusCode() + "）");
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("image/png").split(";")[0];
            String extension = contentType.contains("jpeg") ? "jpg" : contentType.contains("webp") ? "webp" : "png";
            return new GeneratedImage(response.body(), contentType, extension);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("下载生成图片被中断", e);
        } catch (Exception e) {
            throw new IllegalStateException("下载生成图片失败：" + e.getMessage(), e);
        }
    }

    private String trimTrailingSlash(String value) {
        return value != null && value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    public record GeneratedImage(byte[] bytes, String contentType, String extension) {}
}
