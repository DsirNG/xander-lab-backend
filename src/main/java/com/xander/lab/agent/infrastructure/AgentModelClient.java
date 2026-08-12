package com.xander.lab.agent.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.blogagent.infrastructure.BlogAgentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Calls an OpenAI-compatible Responses endpoint to drive the agent loop.
 * Credentials fall back to the shared blog.agent configuration so one API
 * key serves both the blog generation pipeline and the orchestration agent.
 */
@Slf4j
@Component
public class AgentModelClient {

    private final BlogAgentProperties blogAgentProperties;
    private final ObjectMapper objectMapper;

    public AgentModelClient(BlogAgentProperties blogAgentProperties, ObjectMapper objectMapper) {
        this.blogAgentProperties = blogAgentProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * Asks the model to pick the next step: either conclude with a reply or
     * invoke one of the provided tools. Returns a JSON decision object.
     */
    public JsonNode decideNextStep(String context, String toolDescriptions) {
        String apiKey = blogAgentProperties.getApiKey();
        String model = blogAgentProperties.getModel();
        if (!StringUtils.hasText(apiKey) || !StringUtils.hasText(model)) {
            throw new IllegalStateException("博客智能体尚未配置模型服务，请设置 BLOG_AGENT_API_KEY 和 BLOG_AGENT_MODEL");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("store", false);
        payload.put("instructions", instructions(toolDescriptions));
        payload.put("input", context);
        if (blogAgentProperties.isWebSearchEnabled()) {
            payload.put("tools", java.util.List.of(Map.of("type", "web_search")));
        }
        try {
            JsonNode response = restClient().post()
                    .uri("/responses")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);
            String text = extractOutputText(response);
            if (!StringUtils.hasText(text)) {
                throw new IllegalStateException("模型没有返回决策结果");
            }
            return objectMapper.readTree(stripCodeFence(text));
        } catch (RestClientResponseException e) {
            throw new IllegalStateException("模型服务请求失败（HTTP " + e.getStatusCode().value() + "），请稍后重试");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("模型决策结果不是有效的 JSON，请重试", e);
        }
    }

    private RestClient restClient() {
        return RestClient.builder()
                .baseUrl(trimTrailingSlash(blogAgentProperties.getBaseUrl()))
                .defaultHeader("Authorization", "Bearer " + blogAgentProperties.getApiKey())
                .build();
    }

    private String instructions(String toolDescriptions) {
        return "你是 Xander Lab 博客工作台的智能体调度器。你的任务是把用户的对话诉求拆解成可执行步骤，"
                + "通过调用工具完成任务，并在完成后用中文回复用户。\n"
                + "可调用工具：\n" + toolDescriptions + "\n"
                + "决策规则：\n"
                + "1. 需要执行操作时，调用工具并等待工具结果；不要臆造工具结果。\n"
                + "2. 工具执行失败时，尝试换一种工具参数或说明失败原因并结束。\n"
                + "3. 用户诉求已达成、或无需调用任何工具时，直接给出最终回复。\n"
                + "4. 必须且只能返回 JSON，不要输出 JSON 之外的任何文字。JSON 结构：\n"
                + "{\"thought\":\"本步的简要思考\",\"done\":false,\"reply\":\"最终回复，done 为 true 时必须填写\","
                + "\"action\":{\"tool\":\"工具名\",\"args\":{\"工具参数名\":\"值\"}}}。\n"
                + "当需要调用工具时 done 为 false 且 action 指向要调用的工具；"
                + "当准备向用户输出最终结果时 done 为 true 且 reply 填写最终回复。";
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

    private String stripCodeFence(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                return trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return trimmed;
    }

    private String trimTrailingSlash(String value) {
        return value != null && value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}