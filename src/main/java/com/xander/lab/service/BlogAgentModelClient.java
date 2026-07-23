package com.xander.lab.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.config.BlogAgentProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Calls a Responses-compatible model endpoint. Model credentials are configuration only. */
@Component
@RequiredArgsConstructor
public class BlogAgentModelClient {
    private final BlogAgentProperties properties;
    private final ObjectMapper objectMapper;

    public JsonNode createArticle(String input, String inputType, String audience, String tone) {
        if (!StringUtils.hasText(properties.getApiKey()) || !StringUtils.hasText(properties.getModel())) {
            throw new IllegalStateException("博客智能体尚未配置模型服务，请设置 BLOG_AGENT_API_KEY 和 BLOG_AGENT_MODEL");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", properties.getModel());
        payload.put("store", false);
        payload.put("instructions", instructions());
        payload.put("input", "输入类型：" + inputType + "\n目标读者：" + audience + "\n写作语气：" + tone + "\n\n用户输入：\n" + input);
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

        String text = response == null ? null : response.path("output_text").asText(null);
        if (!StringUtils.hasText(text)) {
            throw new IllegalStateException("模型没有返回可读取的文章结果");
        }
        try {
            return objectMapper.readTree(stripCodeFence(text));
        } catch (Exception e) {
            throw new IllegalStateException("模型返回格式不正确，请重试", e);
        }
    }

    private String instructions() {
        return "你是 Xander Lab 的知识博客编辑智能体。把用户给出的主题或日记，写成可发布的中文 Markdown 知识博客。"
                + "你必须先澄清文章角度，再使用网页搜索工具核验或补充所有外部事实。用户的个人经历应保留为作者经历，不能伪装成普遍事实。"
                + "没有可靠来源时，不得写成确定事实。输出必须是且仅是 JSON："
                + "{\"title\":string,\"summary\":string,\"content\":string,\"outline\":string,\"categoryId\":string,\"tags\":[string],\"review\":string,\"sources\":[{\"title\":string,\"url\":string,\"publisher\":string,\"excerpt\":string,\"reliability\":string}]}。"
                + "content 必须是完整文章，不含参考资料列表；文章末尾追加“## 参考资料”，以 Markdown 链接列出 sources。"
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
