package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.blogagent.spi.BlogAgentImage;
import com.xander.lab.blogagent.spi.BlogAgentImagePort;
import com.xander.lab.blogagent.spi.BlogAgentImageUsage;
import com.xander.lab.content.api.MediaAssetPort;
import com.xander.lab.content.api.MediaAssetRef;
import com.xander.lab.payment.api.BillingBiz;
import com.xander.lab.payment.api.BillingPort;
import com.xander.lab.payment.api.TokenUsage;
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

    private final BlogAgentImagePort imageGenerationPort;
    private final MediaAssetPort mediaAssetPort;
    private final BillingPort billingPort;
    private final ObjectMapper objectMapper;

    public ImageGenerationTool(BlogAgentImagePort imageGenerationPort, MediaAssetPort mediaAssetPort,
                               BillingPort billingPort, ObjectMapper objectMapper) {
        this.imageGenerationPort = imageGenerationPort;
        this.mediaAssetPort = mediaAssetPort;
        this.billingPort = billingPort;
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
        if (!imageGenerationPort.isEnabled()) {
            throw new IllegalStateException("图片生成模型尚未配置，暂不能生成图片");
        }
        onProgress.onProgress("正在根据描述生成图片…");
        BlogAgentImage generated;
        try {
            generated = imageGenerationPort.generate(TOOL_IMAGE_STYLE + prompt);
        } catch (RuntimeException exception) {
            log.warn("agent image model call failed userId={} type={} message={}",
                    userId, exception.getClass().getSimpleName(), safeLogMessage(exception));
            throw new IllegalStateException("图片生成模型调用失败，请检查图片模型接口配置或供应商状态", exception);
        }
        onProgress.checkCancellation();

        String title = defaultText(args.path("title").asText(), "AI 生成图片");
        String fileName = "agent-image-" + System.currentTimeMillis() + "." + generated.extension();
        String meta = writeMeta(title, prompt, generated.model(), generated.size());
        MediaAssetRef asset;
        try {
            // 只把字节与类型送进内容域：usage / model 属于计费口径，端口故意不携带，
            // 留在调用方（见下方 chargeTokens）。
            asset = mediaAssetPort.recordGenerated(userId, null, fileName,
                    generated.bytes(), generated.contentType(), meta);
        } catch (RuntimeException exception) {
            log.warn("agent image asset save failed userId={} type={} message={}",
                    userId, exception.getClass().getSimpleName(), safeLogMessage(exception));
            throw new IllegalStateException("图片已经生成，但保存到素材库失败", exception);
        }
        try {
            // 契约口径 BlogAgentImageUsage -> 计费契约口径 TokenUsage：分量同名同序，所以这是一次
            // 直接映射。两个契约都不许依赖对方（只允许 JDK + 本包），换算只能落在边界上。
            // 端口保证 usage 非空（没拿到用量时给 NONE），所以这里不做 null 兜底。
            BlogAgentImageUsage billed = generated.usage();
            billingPort.chargeTokens(userId, BillingBiz.BLOG_AGENT_ILLUSTRATION,
                    "agent-image:" + asset.id(), "asset:" + asset.id(),
                    new TokenUsage(billed.inputTokens(), billed.cachedTokens(), billed.outputTokens()),
                    generated.model(), "智能体生成图片");
        } catch (RuntimeException exception) {
            log.warn("agent image billing failed userId={} assetId={} type={} message={}",
                    userId, asset.id(), exception.getClass().getSimpleName(), safeLogMessage(exception));
            throw new IllegalStateException("图片已经生成并保存，但积分扣费记录失败", exception);
        }

        ObjectNode outcome = JsonNodeFactory.instance.objectNode();
        outcome.put("ok", true);
        outcome.put("assetId", asset.id());
        outcome.put("url", asset.url());
        outcome.put("title", title);
        outcome.put("width", asset.width() == null ? -1 : asset.width());
        outcome.put("height", asset.height() == null ? -1 : asset.height());
        outcome.put("contentType", defaultText(asset.contentType(), ""));
        outcome.put("hint", "图片已生成并保存到你的素材库。如需生成其他图片，请继续描述要求。");
        return outcome;
    }

    /**
     * 素材的 {@code meta}。{@code model} 与 {@code size} 取自<b>端口返回的结果</b>，
     * 不是 {@code blog.agent} 的配置项 —— 配置留在提供方（见 {@code BlogAgentImagePort}）。
     *
     * <p>⚠️ {@code model} 记的是<b>实际产出这张图的端点模型</b>。提供方会在候选端点之间兜底重试，
     * 所以它可能是兜底端点而不是首选端点；走管理台网关路径时配置里的首选模型甚至可能是空的。
     * 改这一处之前，这里写的是配置值，于是一次兜底生成会把兜底端点的产物记成首选模型的名字。</p>
     */
    private String writeMeta(String title, String prompt, String model, String size) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "title", title,
                    "prompt", prompt,
                    "style", "tool",
                    "model", defaultText(model, ""),
                    "size", defaultText(size, "")));
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
