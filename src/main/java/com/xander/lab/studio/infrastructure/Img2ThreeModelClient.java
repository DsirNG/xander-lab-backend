package com.xander.lab.studio.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.admin.application.ModelGateway;
import com.xander.lab.admin.application.ModelGateway.ModelEndpoint;
import com.xander.lab.blogagent.infrastructure.BlogAgentProperties;
import com.xander.lab.common.ModelCallResult;
import com.xander.lab.common.ModelUsage;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Vision-capable Responses client for image → procedural Three.js reconstruction.
 * Credentials fall back to blog.agent when img2three.agent fields are blank.
 * When the admin model gateway returns candidates for the img2three_vision
 * feature they take precedence (primary first, fallback second).
 */
@Component
@RequiredArgsConstructor
public class Img2ThreeModelClient {
    private static final Logger log = LoggerFactory.getLogger(Img2ThreeModelClient.class);
    private static final int MAX_OUTPUT_TOKENS = 16_000;
    private final Img2ThreeProperties properties;
    private final BlogAgentProperties blogAgentProperties;
    private final ObjectMapper objectMapper;
    private final ModelGateway gateway;

    public ModelCallResult reconstruct(byte[] imageBytes, String contentType, String starterSpecJson) {
        RuntimeException lastFailure = new IllegalStateException(
                "图片转 3D 尚未配置模型服务，请设置 IMG2THREE_AGENT_API_KEY / IMG2THREE_AGENT_MODEL（或复用 BLOG_AGENT_*）");
        List<ModelEndpoint> endpoints = endpoints();
        for (int index = 0; index < endpoints.size(); index++) {
            ModelEndpoint endpoint = endpoints.get(index);
            try {
                ModelCallResult response = invokeResponsesApi(endpoint,
                        reconstructPayload(endpoint, imageBytes, contentType, starterSpecJson));
                return parseResult(response, "重建");
            } catch (RuntimeException e) {
                lastFailure = e;
                if (index == endpoints.size() - 1) {
                    throw e;
                }
                log.warn("img2three 主模型端点（provider {}）失败，切换兜底模型", endpoint.providerId());
            }
        }
        throw lastFailure;
    }

    public ModelCallResult repair(byte[] imageBytes, String contentType, JsonNode previousResult, String gateFeedback) {
        RuntimeException lastFailure = new IllegalStateException(
                "图片转 3D 尚未配置模型服务，请设置 IMG2THREE_AGENT_API_KEY / IMG2THREE_AGENT_MODEL（或复用 BLOG_AGENT_*）");
        List<ModelEndpoint> endpoints = endpoints();
        for (int index = 0; index < endpoints.size(); index++) {
            ModelEndpoint endpoint = endpoints.get(index);
            try {
                ModelCallResult response = invokeResponsesApi(endpoint,
                        repairPayload(endpoint, imageBytes, contentType, previousResult, gateFeedback));
                return parseResult(response, "修订");
            } catch (RuntimeException e) {
                lastFailure = e;
                if (index == endpoints.size() - 1) {
                    throw e;
                }
                log.warn("img2three 主模型端点（provider {}）失败，切换兜底模型", endpoint.providerId());
            }
        }
        throw lastFailure;
    }

    /** 解析候选端点：网关启用时由功能模型配置驱动（未配置由网关抛错）；网关关闭时复用原环境变量回退链（img2three.agent → blog.agent）。 */
    private List<ModelEndpoint> endpoints() {
        List<ModelEndpoint> candidates = gateway.candidates(ModelGateway.FEATURE_IMG2THREE_VISION);
        if (!candidates.isEmpty()) {
            return candidates;
        }
        String apiKey = resolveApiKey();
        String model = resolveModel();
        if (!StringUtils.hasText(apiKey) || !StringUtils.hasText(model)) {
            throw new IllegalStateException("图片转 3D 尚未配置模型服务，请设置 IMG2THREE_AGENT_API_KEY / IMG2THREE_AGENT_MODEL（或复用 BLOG_AGENT_*）");
        }
        return List.of(new ModelEndpoint(0L, resolveBaseUrl(), apiKey, model));
    }

    private Map<String, Object> reconstructPayload(ModelEndpoint endpoint, byte[] imageBytes,
                                                   String contentType, String starterSpecJson) {
        String mime = StringUtils.hasText(contentType) ? contentType : "image/jpeg";
        String dataUrl = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(imageBytes);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", endpoint.model());
        payload.put("store", false);
        payload.put("max_output_tokens", MAX_OUTPUT_TOKENS);
        payload.put("instructions", pipelineInstructions());
        payload.put("input", List.of(Map.of(
                "role", "user",
                "content", List.of(
                        Map.of("type", "input_text", "text",
                                "Reconstruct the reference image as a procedural 3D object. Return one JSON "
                                        + "object only, without Markdown. Use the following current img2threejs "
                                        + "starter spec as the mandatory structural template. Assess and replace "
                                        + "every generic or unassessed value with image-specific evidence; do not "
                                        + "remove template fields or flatten its hierarchy.\n\nSTARTER_SPEC:\n"
                                        + starterSpecJson),
                        Map.of("type", "input_image", "image_url", dataUrl)
                )
        )));
        return payload;
    }

    private Map<String, Object> repairPayload(ModelEndpoint endpoint, byte[] imageBytes, String contentType,
                                              JsonNode previousResult, String gateFeedback) {
        String mime = StringUtils.hasText(contentType) ? contentType : "image/jpeg";
        String dataUrl = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(imageBytes);
        String repairPrompt = "Repair the previous img2threejs result using the strict validator feedback. "
                + "Return exactly {\"title\": string, \"analysis\": object, \"spec\": ObjectSculptSpec}. "
                + "Preserve every valid, image-specific field; correct every reported issue without lowering the "
                + "quality bar, deleting required structures, inventing persistent assets, or changing the subject. "
                + "Reinspect the attached reference image before correcting geometry, materials, evidence, and "
                + "component contracts.\n\nVALIDATOR_FEEDBACK:\n" + gateFeedback
                + "\n\nPREVIOUS_RESULT:\n" + previousResult;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", endpoint.model());
        payload.put("store", false);
        payload.put("max_output_tokens", MAX_OUTPUT_TOKENS);
        payload.put("instructions", pipelineInstructions());
        payload.put("input", List.of(Map.of(
                "role", "user",
                "content", List.of(
                        Map.of("type", "input_text", "text", repairPrompt),
                        Map.of("type", "input_image", "image_url", dataUrl)
                )
        )));
        return payload;
    }

    private ModelCallResult parseResult(ModelCallResult response, String stage) {
        String text = extractOutputText(response.payload());
        if (!StringUtils.hasText(text)) {
            throw new IllegalStateException("模型没有返回可读取的" + stage + "结果");
        }
        try {
            return ModelCallResult.of(parseJsonObject(text), response.usage(), response.model());
        } catch (Exception e) {
            throw new IllegalStateException("模型" + stage + "结果不是有效 JSON，请重试", e);
        }
    }

    private String instructions() {
        return "你是 DinQorAI 的过程式 Three.js 重建助手，灵感来自 img2threejs。"
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
                + "Preserve the supplied current-version starter structure and fully author preSpecAssessment, "
                + "terminologyProfile, scores, qualityTargets, qualityContract, actionReadiness, selfCorrectLoop, "
                + "featureReviewTargets, buildPasses, sculptPipeline, lookDevTargets, componentTree, materials, "
                + "and detailInventory. Set qualityContract.qualityBar from the assessed complexity. Keep "
                + "sculptPipeline.passGateMode as locked-sequential and passOrder synchronized with buildPasses. "
                + "Replace generic starter featureReviewTargets with image-specific semantic systems. "
                + "Resolve every preSpecAssessment.unknownsToResolveBeforeImplementation item before returning; "
                + "use conservative visible-image inference and record hidden geometry as inferred elsewhere, "
                + "then return unknownsToResolveBeforeImplementation as an empty array. "
                + "The inline reference image is not a persistent PBR asset URL. Keep spec.sourceImage as an empty "
                + "string and do not invent referencePbr map paths. Only set sourceImage/referencePbr when real "
                + "extract_pbr_evidence.py outputs with persistent usable map URLs are supplied. "
                + "Every componentTree item must include a valid topologyClass and an evidence-based "
                + "topologyRationale. Use continuous-sculpt for connected organic/anatomical volumes, fiber-strand "
                + "for whiskers/hair/fibers, conforming-shell for thin layers following another surface, "
                + "assembled-solid for rigid assembled parts, surface-relief for relief detail, and material-only "
                + "only when no geometry is represented. The rationale must cite the observed form and must not "
                + "merely repeat the enum value. Every component whose topologyClass is not material-only must "
                + "also include colorMaterialRecipe with dominantAlbedo and secondaryAlbedo formatted exactly as "
                + "rgba(r, g, b, a), materialClass set to metal, plastic, wood, fabric, skin, glass, ceramic, "
                + "rubber, stone, or unknown, materialClassConfidence from 0 to 1, and evidenceRefs tied to the "
                + "component's visible image region. If colorGradient is present, its type must be linear or "
                + "radial and its stops must contain at least two entries with rgba colors. Use skin for organic "
                + "fur/body surfaces when no dedicated fur enum exists, glass for cornea, and fabric for "
                + "fiber-like whiskers. "
                + "Any attached child component such as an ear, limb, tail, whisker, handle, branch, cable, horn, "
                + "wing, or connector must include attachment with parentId or parentSocket, localStart and "
                + "localEnd numeric 3D vectors, contactType, positive embedDepth or overlap, non-negative "
                + "gapTolerance, and evidenceRefs. attachment.parentId must equal the component parent. "
                + "Build a real macro/meso/micro component hierarchy; "
                + "map every identity-defining detail to a component or material; declare sockets/action profiles "
                + "for moveable parts. Populate all complexity scores and minimum component/detail/material counts. "
                + "Mark hidden geometry as inferred. Do not fake structure with a flat texture. "
                + "The returned spec is passed to img2threejs strict validation, so satisfy its quality contract.";
    }

    private ModelCallResult invokeResponsesApi(ModelEndpoint endpoint, Map<String, Object> payload) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(20));
        // ObjectSculptSpec responses can take minutes; this bound still guarantees durable failure recovery.
        requestFactory.setReadTimeout(Duration.ofMinutes(4));
        try {
            JsonNode body = RestClient.builder()
                    .baseUrl(trimTrailingSlash(endpoint.baseUrl()))
                    .requestFactory(requestFactory)
                    .defaultHeader("Authorization", "Bearer " + endpoint.apiKey())
                    .build()
                    .post()
                    .uri("/responses")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);
            return ModelCallResult.of(body, ModelUsage.fromJson(body == null ? null : body.path("usage")),
                    endpoint.model());
        } catch (Exception e) {
            String reason = describeFailure(e);
            log.warn("img2three Responses API request failed: {}", reason, e);
            throw new IllegalStateException("Image-to-3D model request failed or timed out: " + reason, e);
        }
    }

    private String describeFailure(Exception error) {
        if (error instanceof RestClientResponseException responseError) {
            String body = responseError.getResponseBodyAsString();
            String summary = body == null ? "" : body.replaceAll("\\s+", " ").trim();
            if (summary.length() > 500) summary = summary.substring(0, 500);
            return "HTTP " + responseError.getStatusCode().value()
                    + (summary.isBlank() ? "" : " " + summary);
        }
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String message = root.getMessage();
        return root.getClass().getSimpleName()
                + (StringUtils.hasText(message) ? ": " + message : "");
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
