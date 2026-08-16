package com.xander.lab.admin.application;

import java.util.List;

/**
 * 运行时模型网关：按功能解析候选模型端点。
 *
 * <p>供应商与「功能 → 主/兜底模型」指派均由 admin 模块持久化。网关启用后，
 * 各功能的模型调用只能由「功能模型配置」驱动：无可用配置时抛
 * {@link FeatureModelNotConfiguredException}，禁止回退到环境变量。
 * 网关关闭时返回空列表，各模型客户端继续使用环境变量配置，行为与网关功能
 * 引入前完全一致。agent / blogagent / studio 的模型客户端只能通过本接口
 * 读取解析结果，不得直接访问 admin 的表或 mapper。</p>
 */
public interface ModelGateway {

    /** 功能标识：智能体对话（agent 模块的调度循环）。 */
    String FEATURE_AGENT = "agent";

    /** 功能标识：博客生成各阶段（策划/研究/写作/审校/合规）。 */
    String FEATURE_BLOG_AGENT = "blog_agent";

    /** 功能标识：博客插图生成。 */
    String FEATURE_BLOG_AGENT_IMAGE = "blog_agent_image";

    /** 功能标识：图生3D 视觉重建与修复。 */
    String FEATURE_IMG2THREE_VISION = "img2three_vision";

    /** 接口风格：POST /images/generations（prompt + size，OpenAI 传统图片接口）。 */
    String API_STYLE_IMAGES_GENERATIONS = "IMAGES_GENERATIONS";

    /** 接口风格：POST /chat/completions（messages + modalities，OpenAI 官方 gpt-image 形态）。 */
    String API_STYLE_CHAT_COMPLETIONS = "CHAT_COMPLETIONS";

    /** 接口风格：POST /chat/completions（顶层 prompt + size + n，聚合网关的 gpt-image 形态，如 okinto）。 */
    String API_STYLE_CHAT_PROMPT = "CHAT_PROMPT";

    /** 接口风格：POST /responses（Responses API，文本类功能）。 */
    String API_STYLE_RESPONSES = "RESPONSES";

    /** 未配置接口风格时按功能取的默认值。 */
    static String defaultApiStyle(String featureKey) {
        return FEATURE_BLOG_AGENT_IMAGE.equals(featureKey)
                ? API_STYLE_CHAT_COMPLETIONS : API_STYLE_RESPONSES;
    }

    /**
     * 返回某功能的有序候选端点（主优先，兜底其次）。
     * 网关关闭时返回空列表，调用方回退到自己的环境变量配置；网关启用后，
     * 功能没有可用配置（无配置行/配置停用/主兜底均无可用端点）会抛出
     * {@link FeatureModelNotConfiguredException}，调用方必须向上传播而不回退。
     */
    List<ModelEndpoint> candidates(String featureKey);

    /** 无副作用的空网关，用于保留仅依赖环境变量的构造器（测试或独立组件）。 */
    static ModelGateway disabled() {
        return featureKey -> List.of();
    }

    /**
     * 一次可调用的模型端点候选。
     *
     * @param providerId 所属 model_provider.id（环境变量回退时为 0）
     * @param baseUrl    OpenAI 兼容 API 基础地址（已去除末尾斜杠）
     * @param apiKey     解密后的明文 API 密钥，仅存在于内存
     * @param model      该端点上使用的模型名
     * @param apiStyle   接口风格（IMAGES_GENERATIONS / CHAT_COMPLETIONS / RESPONSES），
     *                   由 ModelGatewayService 按功能配置补默认值；环境变量回退时由调用方提供
     */
    record ModelEndpoint(long providerId, String baseUrl, String apiKey, String model, String apiStyle) {

        /** 兼容仅依赖环境变量的调用方：不指定接口风格，由客户端按功能默认。 */
        public ModelEndpoint(long providerId, String baseUrl, String apiKey, String model) {
            this(providerId, baseUrl, apiKey, model, "");
        }
    }
}