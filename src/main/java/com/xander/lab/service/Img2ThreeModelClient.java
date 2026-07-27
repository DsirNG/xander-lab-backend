package com.xander.lab.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.config.BlogAgentProperties;
import com.xander.lab.config.Img2ThreeProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Vision-capable Responses client for image → procedural Three.js reconstruction.
 * Credentials fall back to blog.agent when img2three.agent fields are blank.
 */
@Component
@RequiredArgsConstructor
public class Img2ThreeModelClient {
    private final Img2ThreeProperties properties;
    private final BlogAgentProperties blogAgentProperties;
    private final ObjectMapper objectMapper;

    public JsonNode reconstruct(byte[] imageBytes, String contentType) {
        String apiKey = resolveApiKey();
        String model = resolveModel();
        String baseUrl = resolveBaseUrl();
        if (!StringUtils.hasText(apiKey) || !StringUtils.hasText(model)) {
            throw new IllegalStateException("图片转 3D 尚未配置模型服务，请设置 IMG2THREE_AGENT_API_KEY / IMG2THREE_AGENT_MODEL（或复用 BLOG_AGENT_*）");
        }

        String mime = StringUtils.hasText(contentType) ? contentType : "image/jpeg";
        String dataUrl = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(imageBytes);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("store", false);
        payload.put("instructions", pipelineInstructions());
        payload.put("input", List.of(Map.of(
                "role", "user",
                "content", List.of(
                        Map.of("type", "input_text", "text",
                                "请根据参考图重建硬表面物体。只返回一个 JSON 对象，不要 Markdown 围栏。"),
                        Map.of("type", "input_image", "image_url", dataUrl)
                )
        )));

        JsonNode response = RestClient.builder()
                .baseUrl(trimTrailingSlash(baseUrl))
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build()
                .post()
                .uri("/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(JsonNode.class);

        String text = extractOutputText(response);
        if (!StringUtils.hasText(text)) {
            throw new IllegalStateException("模型没有返回可读取的重建结果");
        }
        try {
            return parseJsonObject(text);
        } catch (Exception e) {
            throw new IllegalStateException("模型返回不是有效 JSON，请重试", e);
        }
    }

    private String instructions() {
        return "你是 Xander Lab 的过程式 Three.js 重建助手，灵感来自 img2threejs。"
                + "根据一张参考图，输出可在浏览器用白名单几何拼出的近似 3D 模型。"
                + "优先硬表面物体；无法辨认时诚实简化，不要伪造照片里不存在的精细细节。"
                + "输出必须是且仅是 JSON："
                + "{\"title\":string,"
                + "\"analysis\":{\"subject\":string,\"angle\":string,\"materials\":[string],\"notes\":string},"
                + "\"sceneSpec\":{"
                + "\"title\":string,"
                + "\"camera\":{\"position\":[number,number,number],\"target\":[number,number,number],\"fov\":number},"
                + "\"nodes\":[{\"id\":string,\"type\":\"box|sphere|cylinder|cone|torus|plane|group\","
                + "\"parent\":string|null,\"position\":[number,number,number],\"rotation\":[number,number,number],"
                + "\"scale\":[number,number,number],\"size\":[number,number,number],\"radius\":number,"
                + "\"height\":number,\"tube\":number,\"color\":string,\"metalness\":number,\"roughness\":number}]},"
                + "\"factoryCode\":string}。"
                + "sceneSpec.nodes 最多 40 个；type 只能是上述枚举；color 用 #RRGGBB；"
                + "坐标以物体为中心，整体尺寸大致落在边长 2 的包围盒内。"
                + "factoryCode 必须是完整可编译的 TypeScript 字符串：导出 function createModel(): THREE.Group，"
                + "仅使用 three 的原始几何与 MeshStandardMaterial，不要 import，不要网络请求，不要 eval。"
                + "factoryCode 供用户下载阅读，前端不会执行它；预览只使用 sceneSpec。";
    }

    private String pipelineInstructions() {
        return "You are the vision-authoring stage of the img2threejs pipeline. "
                + "Return JSON only: {\"title\": string, \"analysis\": object, \"spec\": object}. "
                + "spec must be a complete img2threejs ObjectSculptSpec, not a legacy sceneSpec or factoryCode. "
                + "Include target, componentTree, materials, detailInventory, qualityContract, sculptPipeline, "
                + "reviewViewpoints, and lookDevTargets. Build a real macro/meso/micro component hierarchy; "
                + "map every identity-defining detail to a component or material; declare sockets/action profiles "
                + "for moveable parts. Mark hidden geometry as inferred. Do not fake structure with a flat texture. "
                + "The returned spec is passed to img2threejs strict validation, so satisfy its quality contract.";
    }

    private String resolveApiKey() {
        return StringUtils.hasText(properties.getApiKey())
                ? properties.getApiKey()
                : blogAgentProperties.getApiKey();
    }

    private String resolveModel() {
        return StringUtils.hasText(properties.getModel())
                ? properties.getModel()
                : blogAgentProperties.getModel();
    }

    private String resolveBaseUrl() {
        if (StringUtils.hasText(properties.getBaseUrl())) return properties.getBaseUrl();
        return blogAgentProperties.getBaseUrl();
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
            int firstNl = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNl > 0 && lastFence > firstNl) {
                return trimmed.substring(firstNl + 1, lastFence).trim();
            }
        }
        return trimmed;
    }

    /**
     * Models occasionally add a short sentence before/after the requested JSON.  Accept one complete
     * top-level object, while keeping the response contract strict enough to reject malformed output.
     */
    private JsonNode parseJsonObject(String text) throws Exception {
        String normalized = stripCodeFence(text);
        try {
            return objectMapper.readTree(normalized);
        } catch (Exception ignored) {
            // Fall through and find the first balanced JSON object outside quoted strings.
        }
        for (int start = normalized.indexOf('{'); start >= 0; start = normalized.indexOf('{', start + 1)) {
            int depth = 0;
            boolean quoted = false;
            boolean escaped = false;
            for (int i = start; i < normalized.length(); i++) {
                char current = normalized.charAt(i);
                if (quoted) {
                    if (escaped) escaped = false;
                    else if (current == '\\') escaped = true;
                    else if (current == '"') quoted = false;
                    continue;
                }
                if (current == '"') quoted = true;
                else if (current == '{') depth++;
                else if (current == '}' && --depth == 0) {
                    try {
                        return objectMapper.readTree(normalized.substring(start, i + 1));
                    } catch (Exception ignored) {
                        break;
                    }
                }
            }
        }
        throw new IllegalArgumentException("No complete JSON object found in model output");
    }

    private String trimTrailingSlash(String url) {
        if (url == null) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
