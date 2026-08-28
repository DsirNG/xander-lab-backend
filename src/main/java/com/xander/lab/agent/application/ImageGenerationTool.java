package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.billing.application.BillingService;
import com.xander.lab.billing.domain.BillingBiz;
import com.xander.lab.blogagent.infrastructure.BlogAgentImageClient;
import com.xander.lab.blogagent.infrastructure.BlogAgentProperties;
import com.xander.lab.publishing.application.BlogMediaService;
import com.xander.lab.publishing.domain.BlogMediaAsset;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * Generates a standalone knowledge image from a prompt and saves it into the
 * user's private media library. Independent from the blog generation pipeline:
 * it can be invoked for a cover, an illustration, or any custom visual.
 */
@Slf4j
@Component
public class ImageGenerationTool implements AgentTool {
    private static final java.util.regex.Pattern HTTP_STATUS =
            java.util.regex.Pattern.compile("(?i)(?:HTTP\\s*)?([1-5]\\d{2})\\b");

    /**
     * 独立图片工具专属风格，与博客智能体插图风格（BlogAgentService 中的
     * "准确、克制、专业的知识插图"）相互独立，避免工具产出与博客插图同质化。
     */
    static final String TOOL_IMAGE_STYLE =
            "为知识内容生成一张构图完整、风格鲜明、适合展示的独立配图或封面图，"
                    + "画面主次分明、层次丰富，不要添加水印。"
                    + "如果包含文字，确保文字简短清晰。要求：";

    private final BlogAgentImageClient imageClient;
    private final BlogMediaService mediaService;
    private final BlogAgentProperties properties;
    private final BillingService billingService;
    private final ObjectMapper objectMapper;

    public ImageGenerationTool(BlogAgentImageClient imageClient, BlogMediaService mediaService,
                               BlogAgentProperties properties, BillingService billingService,
                               ObjectMapper objectMapper) {
        this.imageClient = imageClient;
        this.mediaService = mediaService;
        this.properties = properties;
        this.billingService = billingService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "image_generate";
    }

    @Override
    public String description() {
        return "根据用户描述生成一张知识插图或封面图，保存到用户的个人素材库并返回图片链接。"
                + "当用户明确要求生成图片、配图、封面、示意图时使用本工具。"
                + "参数 prompt 为图片的详细描述（必须原样包含用户所有关键要求）。";
    }

    @Override
    public JsonNode parametersSchema() {
        JsonNodeFactory factory = JsonNodeFactory.instance;
        ObjectNode schema = factory.objectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode prompt = properties.putObject("prompt");
        prompt.put("type", "string");
        prompt.put("description", "图片内容的详细描述，包含主题、风格、构图等所有用户要求");
        ObjectNode title = properties.putObject("title");
        title.put("type", "string");
        title.put("description", "图片标题（可选，默认自动生成），用于在素材库中标识这张图片");
        schema.putArray("required").add("prompt");
        return schema;
    }

    @Override
    public JsonNode execute(Long userId, JsonNode args, ProgressListener onProgress) {
        onProgress.checkCancellation();
        String prompt = args.path("prompt").asText("");
        if (!StringUtils.hasText(prompt)) {
            throw new IllegalArgumentException("图片生成工具缺少参数 prompt");
        }
        if (!imageClient.isEnabled()) {
            throw new IllegalStateException("图片生成模型尚未配置，暂不能生成图片");
        }
        onProgress.onProgress("正在根据描述生成图片…");
        BlogAgentImageClient.GeneratedImage generated;
        try {
            generated = imageClient.generate(TOOL_IMAGE_STYLE + prompt);
        } catch (RuntimeException exception) {
            log.warn("agent image model call failed userId={} type={} message={}",
                    userId, exception.getClass().getSimpleName(), safeLogMessage(exception));
            throw new IllegalStateException("图片生成模型调用失败，请检查图片模型接口配置或供应商状态", exception);
        }
        onProgress.checkCancellation();

        String title = defaultText(args.path("title").asText(), "AI 生成图片");
        String fileName = "agent-image-" + System.currentTimeMillis() + "." + generated.extension();
        String meta = writeMeta(title, prompt);
        BlogMediaAsset asset;
        try {
            asset = mediaService.saveAgentImage(userId, null, fileName, generated, meta);
        } catch (RuntimeException exception) {
            log.warn("agent image asset save failed userId={} type={} message={}",
                    userId, exception.getClass().getSimpleName(), safeLogMessage(exception));
            throw new IllegalStateException("图片已经生成，但保存到素材库失败", exception);
        }
        try {
            billingService.chargeTokens(userId, BillingBiz.BLOG_AGENT_ILLUSTRATION,
                    "agent-image:" + asset.getId(), "asset:" + asset.getId(),
                    generated.usage(), generated.model(), "智能体生成图片");
        } catch (RuntimeException exception) {
            log.warn("agent image billing failed userId={} assetId={} type={} message={}",
                    userId, asset.getId(), exception.getClass().getSimpleName(), safeLogMessage(exception));
            throw new IllegalStateException("图片已经生成并保存，但积分扣费记录失败", exception);
        }

        ObjectNode outcome = JsonNodeFactory.instance.objectNode();
        outcome.put("ok", true);
        outcome.put("assetId", asset.getId());
        outcome.put("url", asset.getUrl());
        outcome.put("title", title);
        outcome.put("width", asset.getWidth() == null ? -1 : asset.getWidth());
        outcome.put("height", asset.getHeight() == null ? -1 : asset.getHeight());
        outcome.put("contentType", defaultText(asset.getContentType(), ""));
        outcome.put("hint", "图片已生成并保存到你的素材库。如需生成其他图片，请继续描述要求。");
        return outcome;
    }

    private String writeMeta(String title, String prompt) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "title", title,
                    "prompt", prompt,
                    "style", "tool",
                    "model", defaultText(properties.getImageModel(), ""),
                    "size", defaultText(properties.getImageSize(), "")));
        } catch (Exception e) {
            log.debug("agent image generation meta serialization failed: {}", e.getMessage());
            return "{}";
        }
    }

    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private String safeLogMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (!StringUtils.hasText(message)) return "-";
        var status = HTTP_STATUS.matcher(message);
        if (status.find()) return "HTTP " + status.group(1);
        int newline = message.indexOf('\n');
        String singleLine = newline >= 0 ? message.substring(0, newline) : message;
        return singleLine.length() <= 300 ? singleLine : singleLine.substring(0, 300) + "...";
    }
}
