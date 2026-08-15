package com.xander.lab.admin.application;

import java.util.List;

/**
 * 运行时模型网关：按功能解析候选模型端点。
 *
 * <p>供应商与「功能 → 主/兜底模型」指派均由 admin 模块持久化。当网关总开关关闭、
 * 功能未启用或没有任何可用端点时返回空列表，各模型客户端继续使用环境变量配置，
 * 行为与网关功能引入前完全一致。agent / blogagent / studio 的模型客户端只能通过
 * 本接口读取解析结果，不得直接访问 admin 的表或 mapper。</p>
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

    /**
     * 返回某功能的有序候选端点（主优先，兜底其次）。
     * 空列表表示没有可用的管理台配置，调用方应回退到自己的环境变量配置。
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
     */
    record ModelEndpoint(long providerId, String baseUrl, String apiKey, String model) {
    }
}