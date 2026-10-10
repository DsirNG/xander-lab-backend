import type {
  FeatureModelConfigInput,
  FeatureModelConfigRecord,
  FeatureModelConfigRepository,
} from "../interfaces/feature-model-config-repository.js";

export const MODEL_FEATURES = ["agent", "blog_agent", "blog_agent_image", "img2three_vision", "recitation_asr"];
const API_STYLES = ["IMAGES_GENERATIONS", "CHAT_COMPLETIONS", "CHAT_PROMPT", "RESPONSES", "DASHSCOPE_ASR"];

export type FeatureModelConfigResult =
  | { kind: "saved"; config: FeatureModelConfigRecord }
  | { kind: "invalid"; message: string };

function defaultConfig(featureKey: string): FeatureModelConfigRecord {
  return {
    featureKey,
    enabled: 0,
    primaryProviderId: null,
    primaryModel: null,
    primaryApiStyle: null,
    fallbackProviderId: null,
    fallbackModel: null,
    fallbackApiStyle: null,
    updatedAt: null,
  };
}

export class FeatureModelConfigService {
  constructor(private readonly configs: FeatureModelConfigRepository) {}

  async list(): Promise<FeatureModelConfigRecord[]> {
    const rows = await this.configs.listFeatureConfigs();
    return MODEL_FEATURES.map((key) => rows.find((row) => row.featureKey === key) ?? defaultConfig(key));
  }

  async save(input: FeatureModelConfigInput): Promise<FeatureModelConfigResult> {
    const styles = [input.primaryApiStyle, input.fallbackApiStyle];
    for (const style of styles) {
      if (style && !API_STYLES.includes(style)) {
        return { kind: "invalid", message: `接口风格必须是 ${API_STYLES.join(" / ")}` };
      }
      if (style && input.featureKey === "blog_agent_image" && style === "RESPONSES") {
        return { kind: "invalid", message: "图片生成功能不支持 RESPONSES 接口风格" };
      }
      if (style && input.featureKey !== "blog_agent_image" && ["IMAGES_GENERATIONS", "CHAT_PROMPT"].includes(style)) {
        return { kind: "invalid", message: "文本功能不支持图片专用接口风格" };
      }
      if (style && input.featureKey === "recitation_asr" && style !== "DASHSCOPE_ASR") {
        return { kind: "invalid", message: "背诵音转文功能只支持 DASHSCOPE_ASR 接口风格" };
      }
      if (style && input.featureKey !== "recitation_asr" && style === "DASHSCOPE_ASR") {
        return { kind: "invalid", message: "DASHSCOPE_ASR 仅用于背诵音转文功能" };
      }
    }

    const slots = [
      { name: "primary", providerId: input.primaryProviderId, model: input.primaryModel, style: input.primaryApiStyle },
      { name: "fallback", providerId: input.fallbackProviderId, model: input.fallbackModel, style: input.fallbackApiStyle },
    ];
    for (const slot of slots) {
      const hasProvider = slot.providerId !== null;
      if (hasProvider !== Boolean(slot.model) || hasProvider !== Boolean(slot.style)) {
        return { kind: "invalid", message: `${input.featureKey} 的 ${slot.name} 供应商、模型与接口风格必须同时填写` };
      }
      if (hasProvider && !await this.configs.hasEnabledProvider(slot.providerId as number)) {
        return { kind: "invalid", message: `${slot.name} 供应商不存在或已停用` };
      }
    }
    if (input.primaryProviderId !== null && input.primaryProviderId === input.fallbackProviderId
      && input.primaryModel === input.fallbackModel) {
      return {
        kind: "invalid",
        message: "主模型与兜底模型不能相同（同供应商+同模型时兜底无意义，请配置其他供应商或模型）",
      };
    }
    if (!input.enabled && slots.some((slot) => slot.providerId !== null || slot.model || slot.style)) {
      return { kind: "invalid", message: "功能配置未启用时请清空模型槽位，或先启用该配置" };
    }

    await this.configs.saveFeatureConfig(input);
    const config = await this.configs.findFeatureConfig(input.featureKey);
    return config
      ? { kind: "saved", config }
      : { kind: "invalid", message: "功能模型配置保存失败" };
  }
}
