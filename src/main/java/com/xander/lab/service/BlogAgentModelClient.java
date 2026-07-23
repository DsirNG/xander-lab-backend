package com.xander.lab.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.config.BlogAgentProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Calls a Responses-compatible model endpoint. Model credentials are configuration only. */
@Component
@RequiredArgsConstructor
public class BlogAgentModelClient {
    private final BlogAgentProperties properties;
    private final ObjectMapper objectMapper;

    public JsonNode createArticle(String input) {
        if (!StringUtils.hasText(properties.getApiKey()) || !StringUtils.hasText(properties.getModel())) {
            throw new IllegalStateException("博客智能体尚未配置模型服务，请设置 BLOG_AGENT_API_KEY 和 BLOG_AGENT_MODEL");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", properties.getModel());
        payload.put("store", false);
        payload.put("instructions", instructions());
        payload.put("input", "用户输入：\n" + input);
        if (properties.isWebSearchEnabled()) {
            payload.put("tools", List.of(Map.of("type", "web_search")));
        }

        JsonNode response = RestClient.builder()
                .baseUrl(trimTrailingSlash(properties.getBaseUrl()))
                .defaultHeader("Authorization", "Bearer " + properties.getApiKey())
                .build()
                .post()
                .uri("/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(JsonNode.class);

        String text = extractOutputText(response);
        if (!StringUtils.hasText(text)) {
            throw new IllegalStateException("模型没有返回可读取的文章结果");
        }
        try {
            return objectMapper.readTree(stripCodeFence(text));
        } catch (Exception e) {
            throw new IllegalStateException("模型返回格式不正确，请重试", e);
        }
    }

    /** Streams output tokens from a Responses-compatible endpoint and returns the final JSON article. */
    public JsonNode createArticleStream(String input, Consumer<String> onDelta) {
        Map<String, Object> payload = requestPayload(input);
        payload.put("stream", true);
        HttpURLConnection connection = null;
        StringBuilder output = new StringBuilder();
        try {
            connection = (HttpURLConnection) URI.create(trimTrailingSlash(properties.getBaseUrl()) + "/responses").toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Authorization", "Bearer " + properties.getApiKey());
            connection.setRequestProperty("Content-Type", MediaType.APPLICATION_JSON_VALUE);
            connection.setRequestProperty("Accept", MediaType.TEXT_EVENT_STREAM_VALUE);
            connection.setDoOutput(true);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(0);
            connection.getOutputStream().write(objectMapper.writeValueAsBytes(payload));

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("模型服务请求失败（HTTP " + status + "）：" + readError(connection.getErrorStream()));
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if (data.isEmpty() || "[DONE]".equals(data)) continue;
                    JsonNode event = objectMapper.readTree(data);
                    String type = event.path("type").asText();
                    if ("response.output_text.delta".equals(type)) {
                        String delta = event.path("delta").asText("");
                        output.append(delta);
                        onDelta.accept(delta);
                    } else if ("response.output_text.done".equals(type) && output.isEmpty()) {
                        String text = event.path("text").asText("");
                        output.append(text);
                        onDelta.accept(text);
                    } else if ("response.completed".equals(type) && output.isEmpty()) {
                        String text = extractOutputText(event.path("response"));
                        output.append(text == null ? "" : text);
                    } else if ("error".equals(type)) {
                        throw new IllegalStateException("模型服务返回错误：" + event.path("message").asText("未知错误"));
                    }
                }
            }
            if (!StringUtils.hasText(output)) throw new IllegalStateException("模型没有返回可读取的文章结果");
            return objectMapper.readTree(stripCodeFence(output.toString()));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("读取模型流式结果失败：" + e.getMessage(), e);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private Map<String, Object> requestPayload(String input) {
        if (!StringUtils.hasText(properties.getApiKey()) || !StringUtils.hasText(properties.getModel())) {
            throw new IllegalStateException("博客智能体尚未配置模型服务，请设置 BLOG_AGENT_API_KEY 和 BLOG_AGENT_MODEL");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", properties.getModel());
        payload.put("store", false);
        payload.put("instructions", instructions());
        payload.put("input", "用户输入：\n" + input);
        if (properties.isWebSearchEnabled()) payload.put("tools", List.of(Map.of("type", "web_search")));
        return payload;
    }

    private String extractOutputText(JsonNode response) {
        if (response == null || response.isMissingNode()) return null;
        String direct = response.path("output_text").asText();
        if (StringUtils.hasText(direct)) return direct;
        for (JsonNode output : response.path("output")) {
            for (JsonNode content : output.path("content")) {
                String text = content.path("text").asText();
                if (StringUtils.hasText(text)) return text;
            }
        }
        String chatContent = response.path("choices").path(0).path("message").path("content").asText();
        return StringUtils.hasText(chatContent) ? chatContent : null;
    }

    private String readError(InputStream stream) {
        if (stream == null) return "未知错误";
        try (stream; BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return reader.readLine();
        } catch (Exception ignored) { return "未知错误"; }
    }

    private String instructions() {
        return "你是 Xander Lab 的知识博客编辑智能体。把用户给出的主题、日记或项目问题，写成可发布的中文 Markdown 知识博客。"
                + "文章主干必须来自用户明确提到的主题、问题、场景与目标；你必须自行判断输入性质、合适读者与写作语气，用户不需要填写这些设置。输入形式只影响是否保留作者经历，不得改变文章主线。"
                + "只扩展直接帮助理解、验证或解决用户核心问题的知识。不要为了套模板强行加入因果链、知识图谱、对比、案例或排障清单；这些仅在用户提出、或它们能直接解释核心主题时才加入。"
                + "如用户提到原因、影响、机制、为什么、导致等关系，才应建立清晰的因果链。只有知识节点之间存在多个关键关系且图谱能提升理解时，才启用知识图谱。"
                + "你必须先澄清文章角度，再使用网页搜索工具核验或补充所有外部事实。用户的个人经历应保留为作者经历，不能伪装成普遍事实。"
                + "没有可靠来源时，不得写成确定事实。输出必须是且仅是 JSON："
                + "{\"title\":string,\"summary\":string,\"content\":string,\"outline\":string,\"writingBrief\":{\"inputNature\":string,\"audience\":string,\"tone\":string},\"contentBoundary\":{\"mustCover\":[string],\"relatedExpansion\":[string],\"outOfScope\":[string],\"optionalModules\":[string]},\"knowledgeGraph\":{\"enabled\":boolean,\"reason\":string,\"nodes\":[{\"id\":string,\"label\":string,\"description\":string}],\"edges\":[{\"from\":string,\"to\":string,\"relation\":string}]},\"categoryId\":string,\"tags\":[string],\"review\":string,\"illustrations\":[{\"placeholder\":string,\"title\":string,\"alt\":string,\"prompt\":string,\"purpose\":string}],\"sources\":[{\"title\":string,\"url\":string,\"publisher\":string,\"excerpt\":string,\"reliability\":string}]}。"
                + "writingBrief.inputNature 只能是 topic、project_context 或 journal 之一；audience 最多 120 个字符，tone 最多 60 个字符。"
                + "content 必须是完整文章，不含参考资料列表；文章末尾追加“## 参考资料”，以 Markdown 链接列出 sources。"
                + "仅当插图能显著帮助理解机制、流程、结构、对比或关键关系时才规划插图，最多 3 张，不要为装饰而配图。"
                + "每张插图必须在 content 的准确位置放置唯一占位符，格式严格为 <!-- illustration:英文短标识 -->，并在 illustrations 中返回完全相同的 placeholder。"
                + "illustrations 的 prompt 必须描述需要表达的知识关系、视觉结构、必要标签和风格；alt 必须准确说明图片传递的信息。无需插图时返回空数组，content 中不得出现插图占位符。"
                + "contentBoundary 必须精确说明哪些内容来自用户、允许怎样的直接扩展，以及不应扩展的边界。knowledgeGraph 未启用时，nodes 和 edges 必须为空数组。"
                + "categoryId 只可使用 frontend、backend、architecture、devops、career。sources 中仅保留实际检索到且被用于文章的来源。";
    }

    private String stripCodeFence(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) return trimmed.substring(firstNewline + 1, lastFence).trim();
        }
        return trimmed;
    }

    private String trimTrailingSlash(String value) {
        return value != null && value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
