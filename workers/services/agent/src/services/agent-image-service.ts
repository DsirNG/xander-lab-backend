import type { AgentBillingPort } from "../interfaces/agent-billing-port.js";
import type { AgentImageGenerationPort } from "../interfaces/agent-image-generation-port.js";
import type { AgentImageMediaPort } from "../interfaces/agent-image-media-port.js";
import type { AgentImageModelGateway } from "../interfaces/agent-image-model-gateway.js";

const IMAGE_STYLE = "生成一张构图完整、风格鲜明、适合展示的知识插图或封面图，画面主次分明、层次丰富，不添加水印；如包含文字，保持简短清晰。用户要求：";

export class AgentImageError extends Error {
  constructor(readonly status: number, message: string) { super(message); }
}

export class AgentImageService {
  constructor(
    private readonly gateway: AgentImageModelGateway,
    private readonly generator: AgentImageGenerationPort,
    private readonly media: AgentImageMediaPort,
    private readonly billing: AgentBillingPort,
  ) {}

  async generate(userId: number, conversationId: number, runVersion: number, input: unknown) {
    if (!Number.isSafeInteger(userId) || userId < 1 || !input || typeof input !== "object" || Array.isArray(input)) {
      throw new AgentImageError(400, "图片生成参数无效");
    }
    const value = input as Record<string, unknown>;
    const prompt = typeof value.prompt === "string" ? value.prompt.trim() : "";
    const title = typeof value.title === "string" ? value.title.trim().slice(0, 120) : "";
    if (!prompt || prompt.length > 2_000) throw new AgentImageError(400, "图片生成工具缺少有效参数 prompt");

    let configured: Awaited<ReturnType<AgentImageModelGateway["candidates"]>>;
    try { configured = await this.gateway.candidates(); }
    catch { throw new AgentImageError(503, "图片生成模型配置暂不可用"); }
    if (configured.managed && configured.endpoints.length === 0) {
      throw new AgentImageError(503, "图片生成模型尚未配置");
    }
    if (configured.endpoints.length === 0) throw new AgentImageError(503, "图片生成模型尚未配置");

    let generated: Awaited<ReturnType<AgentImageGenerationPort["generate"]>> | null = null;
    for (const endpoint of configured.endpoints) {
      try {
        generated = await this.generator.generate(endpoint, IMAGE_STYLE + prompt);
        break;
      } catch {
        // A failed provider may be replaced by the next explicitly configured fallback.
      }
    }
    if (!generated) throw new AgentImageError(502, "图片生成模型调用失败，请检查图片模型接口配置或供应商状态");

    const resolvedTitle = title || "AI 生成图片";
    const originalName = `agent-image-${Date.now()}.${generated.extension}`;
    const generationMeta = JSON.stringify({
      title: resolvedTitle, prompt, style: "tool", model: generated.model, size: generated.size,
    });
    let asset: Awaited<ReturnType<AgentImageMediaPort["recordGenerated"]>>;
    try {
      asset = await this.media.recordGenerated(userId, originalName, generated.contentType, generated.bytes, generationMeta);
    } catch {
      throw new AgentImageError(503, "图片已经生成，但保存到素材库失败");
    }
    try {
      await this.billing.chargeTokens({
        userId,
        bizType: "blog-agent-illustration",
        bizKey: `agent-image:${asset.id}`,
        bizRef: `asset:${asset.id}`,
        inputTokens: generated.usage.inputTokens,
        outputTokens: generated.usage.outputTokens,
        cachedTokens: generated.usage.cachedTokens,
        model: generated.model,
        remark: "智能体生成图片",
      });
    } catch {
      throw new AgentImageError(503, "图片已经保存到素材库，但积分扣费记录失败");
    }
    return {
      ok: true, assetId: asset.id, url: asset.url, title: resolvedTitle,
      width: asset.width ?? -1, height: asset.height ?? -1, contentType: asset.contentType,
      hint: "图片已生成并保存到你的素材库。如需生成其他图片，请继续描述要求。",
      conversationId, runVersion,
    };
  }

  async findOwned(userId: number, rawId: unknown) {
    const id = typeof rawId === "number" && Number.isSafeInteger(rawId) ? rawId
      : typeof rawId === "string" && /^[1-9]\d{0,15}$/.test(rawId) ? Number(rawId) : 0;
    if (!Number.isSafeInteger(userId) || userId < 1 || id < 1) throw new AgentImageError(400, "图片素材 ID 无效");
    const asset = await this.media.findOwned(userId, id);
    if (!asset) throw new AgentImageError(404, "找不到属于当前用户的图片素材");
    return { assetId: asset.id, url: asset.url, title: asset.originalName || "图片素材", contentType: asset.contentType };
  }
}
