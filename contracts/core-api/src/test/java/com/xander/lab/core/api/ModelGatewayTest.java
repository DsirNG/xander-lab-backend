package com.xander.lab.core.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住 {@link ModelGateway} 的常量字面量与默认值。
 *
 * <p>这不是一组"常量等于自己"的空测试。{@code FEATURE_*} 与 {@code API_STYLE_*}
 * 会被持久化进 {@code model_feature_config} 的
 * {@code feature_key} / {@code primary_api_style} / {@code fallback_api_style} 列。
 * 改名**不会编译报错**，只会让库里已有的行静默失配：网关开着、配置也在，
 * 但按新键查不到，于是每个模型调用都抛
 * {@link FeatureModelNotConfiguredException}。这属于"静默降低既有能力"，
 * 而常量取值属于契约改动。</p>
 *
 * <p>所以这里逐个断言字面量。任何一次"顺手统一命名"都会在这里失败，
 * 逼着改动者去确认数据怎么迁移。</p>
 */
class ModelGatewayTest {

    @Test
    void featureKeysKeepTheValuesThatArePersistedInTheDatabase() {
        assertEquals("agent", ModelGateway.FEATURE_AGENT);
        assertEquals("blog_agent", ModelGateway.FEATURE_BLOG_AGENT);
        assertEquals("blog_agent_image", ModelGateway.FEATURE_BLOG_AGENT_IMAGE);
        assertEquals("img2three_vision", ModelGateway.FEATURE_IMG2THREE_VISION);
        assertEquals("recitation_asr", ModelGateway.FEATURE_RECITATION_ASR);
    }

    @Test
    void apiStylesKeepTheValuesThatArePersistedInTheDatabase() {
        assertEquals("IMAGES_GENERATIONS", ModelGateway.API_STYLE_IMAGES_GENERATIONS);
        assertEquals("CHAT_COMPLETIONS", ModelGateway.API_STYLE_CHAT_COMPLETIONS);
        assertEquals("CHAT_PROMPT", ModelGateway.API_STYLE_CHAT_PROMPT);
        assertEquals("RESPONSES", ModelGateway.API_STYLE_RESPONSES);
        assertEquals("DASHSCOPE_ASR", ModelGateway.API_STYLE_DASHSCOPE_ASR);
    }

    /**
     * 两个特殊功能各有自己的默认接口风格，其余（文本类）统一回落到 Responses。
     *
     * <p>这三个分支在实现里是 {@code if} 链，删掉任何一条都会让某个功能默认走错协议；
     * 而走错协议的表现是"模型返回了看不懂的响应"，不是编译错误。</p>
     */
    @Test
    void defaultApiStylePicksTheRightProtocolPerFeature() {
        assertEquals(ModelGateway.API_STYLE_CHAT_COMPLETIONS,
                ModelGateway.defaultApiStyle(ModelGateway.FEATURE_BLOG_AGENT_IMAGE));
        assertEquals(ModelGateway.API_STYLE_DASHSCOPE_ASR,
                ModelGateway.defaultApiStyle(ModelGateway.FEATURE_RECITATION_ASR));
        assertEquals(ModelGateway.API_STYLE_RESPONSES,
                ModelGateway.defaultApiStyle(ModelGateway.FEATURE_AGENT));
        assertEquals(ModelGateway.API_STYLE_RESPONSES,
                ModelGateway.defaultApiStyle(ModelGateway.FEATURE_BLOG_AGENT));
        assertEquals(ModelGateway.API_STYLE_RESPONSES,
                ModelGateway.defaultApiStyle(ModelGateway.FEATURE_IMG2THREE_VISION));
    }

    /** 未知功能键也回落到 Responses：默认分支不是"抛异常"，别把它改成抛。 */
    @Test
    void defaultApiStyleFallsBackForUnknownFeatureKeys() {
        assertEquals(ModelGateway.API_STYLE_RESPONSES, ModelGateway.defaultApiStyle("no-such-feature"));
        assertEquals(ModelGateway.API_STYLE_RESPONSES, ModelGateway.defaultApiStyle(null));
    }

    /**
     * 网关关闭时返回空列表 —— 这是"回退到环境变量"这条既有行为的开关。
     *
     * <p>若改成抛异常或返回非空列表，所有只依赖环境变量的部署会立刻改变行为，
     * 而网关功能的整个设计前提就是"关闭时与引入前完全一致"。</p>
     */
    @Test
    void disabledGatewayReportsNoCandidatesInsteadOfThrowing() {
        ModelGateway gateway = ModelGateway.disabled();

        assertTrue(gateway.candidates(ModelGateway.FEATURE_AGENT).isEmpty());
        assertTrue(gateway.candidates("no-such-feature").isEmpty());
        assertTrue(gateway.candidates(null).isEmpty());
    }

    /** 四参便捷构造器把 apiStyle 留空，由客户端按功能取默认值。 */
    @Test
    void fourArgumentEndpointLeavesApiStyleEmptyForTheClientToDefault() {
        ModelGateway.ModelEndpoint endpoint =
                new ModelGateway.ModelEndpoint(0L, "https://example.com/v1", "sk-test", "gpt-4o");

        assertEquals(0L, endpoint.providerId());
        assertEquals("https://example.com/v1", endpoint.baseUrl());
        assertEquals("sk-test", endpoint.apiKey());
        assertEquals("gpt-4o", endpoint.model());
        assertEquals("", endpoint.apiStyle());
    }

    /** 五参构造器原样保留调用方给出的接口风格。 */
    @Test
    void fiveArgumentEndpointKeepsTheGivenApiStyle() {
        ModelGateway.ModelEndpoint endpoint = new ModelGateway.ModelEndpoint(
                7L, "https://example.com/v1", "sk-test", "gpt-image-2",
                ModelGateway.API_STYLE_CHAT_PROMPT);

        assertEquals(7L, endpoint.providerId());
        assertEquals(ModelGateway.API_STYLE_CHAT_PROMPT, endpoint.apiStyle());
    }

    /** 异常消息必须带上功能键，否则管理台只能看到一句没有主语的提示。 */
    @Test
    void featureNotConfiguredMessageNamesTheFeature() {
        FeatureModelNotConfiguredException exception =
                new FeatureModelNotConfiguredException(ModelGateway.FEATURE_RECITATION_ASR);

        assertTrue(exception.getMessage().contains(ModelGateway.FEATURE_RECITATION_ASR));
    }

    /**
     * 三个类型必须同住契约包。
     *
     * <p>它们是一个整体：接口声明"可能抛这个异常"、异常是它的约定行为、
     * 端点是它的返回值。任何一个被挪回 admin 域，跨单元调用方就又多一条 import，
     * 而这件事在指标上不会有人注意到 —— 所以在这里钉住包名。</p>
     */
    @Test
    void theInterfaceItsResultAndItsFailureTypeShareOneContractPackage() {
        String contractPackage = "com.xander.lab.core.api";

        assertEquals(contractPackage, ModelGateway.class.getPackageName());
        assertEquals(contractPackage, ModelGateway.ModelEndpoint.class.getPackageName());
        assertEquals(contractPackage, FeatureModelNotConfiguredException.class.getPackageName());
    }
}
