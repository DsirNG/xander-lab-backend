package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xander.lab.blogagent.spi.BlogAgentImage;
import com.xander.lab.blogagent.spi.BlogAgentImagePort;
import com.xander.lab.blogagent.spi.BlogAgentImageUsage;
import com.xander.lab.content.api.MediaAssetPort;
import com.xander.lab.content.api.MediaAssetRef;
import com.xander.lab.payment.api.BillingBiz;
import com.xander.lab.payment.api.BillingPort;
import com.xander.lab.payment.api.TokenUsage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImageGenerationToolTest {

    private static final Long USER_ID = 42L;

    @Test
    void identifiesImageProviderFailuresWithoutExposingTheRawHttpError() {
        BlogAgentImagePort imagePort = mock(BlogAgentImagePort.class);
        when(imagePort.isEnabled()).thenReturn(true);
        when(imagePort.generate(anyString()))
                .thenThrow(new IllegalStateException("500 Internal Server Error: secret vendor body"));
        ImageGenerationTool tool = newTool(imagePort, mock(MediaAssetPort.class), mock(BillingPort.class));
        var args = JsonNodeFactory.instance.objectNode().put("prompt", "一只小猫");

        assertThatThrownBy(() -> tool.execute(USER_ID, args, noOpProgress()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("图片生成模型调用失败，请检查图片模型接口配置或供应商状态")
                .hasMessageNotContaining("secret vendor body");
    }

    @Test
    void refusesToCallTheProviderWhenTheCapabilityIsNotConfigured() {
        BlogAgentImagePort imagePort = mock(BlogAgentImagePort.class);
        when(imagePort.isEnabled()).thenReturn(false);
        ImageGenerationTool tool = newTool(imagePort, mock(MediaAssetPort.class), mock(BillingPort.class));
        var args = JsonNodeFactory.instance.objectNode().put("prompt", "一只小猫");

        assertThatThrownBy(() -> tool.execute(USER_ID, args, noOpProgress()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("图片生成模型尚未配置，暂不能生成图片");
        verify(imagePort, org.mockito.Mockito.never()).generate(anyString());
    }

    @Test
    void chargesImageGenerationByReportedTokenUsage() {
        BlogAgentImagePort imagePort = mock(BlogAgentImagePort.class);
        when(imagePort.isEnabled()).thenReturn(true);
        when(imagePort.generate(anyString())).thenReturn(
                image(new byte[]{1}, "image/png", "png",
                        new BlogAgentImageUsage(120, 20, 80), "gpt-image-2", "1536x1024"));
        MediaAssetRef asset = assetRef(9L, "https://cdn.example.test/image.png");
        BillingPort billingPort = mock(BillingPort.class);
        MediaAssetPort mediaAssetPort = mock(MediaAssetPort.class);
        when(mediaAssetPort.recordGenerated(anyLong(), any(), anyString(), any(), anyString(), anyString()))
                .thenReturn(asset);
        ImageGenerationTool tool = newTool(imagePort, mediaAssetPort, billingPort);
        var args = JsonNodeFactory.instance.objectNode().put("prompt", "draw a robot");

        tool.execute(USER_ID, args, noOpProgress());

        // 断言的是换算后的值而不是原对象：契约口径 BlogAgentImageUsage(120,20,80) 必须按位置
        // 变成计费口径 TokenUsage(120,20,80)。实参顺序写反不会编译报错，只会静默错账，
        // 所以这里连位置一起钉住（见 TokenUsageTest）。
        verify(billingPort).chargeTokens(USER_ID, BillingBiz.BLOG_AGENT_ILLUSTRATION,
                "agent-image:9", "asset:9", new TokenUsage(120, 20, 80), "gpt-image-2", "智能体生成图片");
    }

    /**
     * ⚠️ 素材 meta 里的 {@code model} 必须是<b>端口返回的那个模型</b>。
     *
     * <p>端口返回的是<b>实际产出这张图的端点</b>：提供方会在候选端点之间兜底重试，所以它可能是
     * 兜底端点而不是首选端点。工具<b>没有</b>配置可读（{@code BlogAgentProperties} 已经不在它的
     * 依赖里），这正是这条断言能成立的原因 —— 它只能写端口给的那个值。</p>
     *
     * <p>改这一处之前，工具读的是 {@code properties.getImageModel()}（配置里的首选模型），
     * 于是一次兜底生成会把兜底端点的产物记成首选模型的名字；走管理台网关路径时那个配置甚至
     * 可能是空的，meta 里就写了个空字符串。图片已经生成、已经入库，没人会发现。</p>
     */
    @Test
    void writesTheProvidersModelAndSizeIntoTheAssetMeta() throws Exception {
        BlogAgentImagePort imagePort = mock(BlogAgentImagePort.class);
        when(imagePort.isEnabled()).thenReturn(true);
        when(imagePort.generate(anyString())).thenReturn(
                image(new byte[]{1}, "image/png", "png", BlogAgentImageUsage.NONE,
                        "实际兜底端点模型", "1536x1024"));
        MediaAssetPort mediaAssetPort = mock(MediaAssetPort.class);
        when(mediaAssetPort.recordGenerated(anyLong(), any(), anyString(), any(), anyString(), anyString()))
                .thenReturn(assetRef(9L, "https://cdn.example.test/image.png"));
        ImageGenerationTool tool = newTool(imagePort, mediaAssetPort, mock(BillingPort.class));
        var args = JsonNodeFactory.instance.objectNode()
                .put("prompt", "draw a robot")
                .put("title", "一张图");

        tool.execute(USER_ID, args, noOpProgress());

        ArgumentCaptor<String> meta = ArgumentCaptor.forClass(String.class);
        verify(mediaAssetPort).recordGenerated(anyLong(), any(), anyString(), any(), anyString(),
                meta.capture());
        JsonNode written = new ObjectMapper().readTree(meta.getValue());
        assertThat(written.path("model").asText()).isEqualTo("实际兜底端点模型");
        assertThat(written.path("size").asText()).isEqualTo("1536x1024");
        assertThat(written.path("title").asText()).isEqualTo("一张图");
        assertThat(written.path("style").asText()).isEqualTo("tool");
    }

    /**
     * 端口保证 {@code usage()} 非空，所以工具不做兜底 —— 这条钉住"空用量"照样能记账。
     *
     * <p>{@link BlogAgentImageUsage#NONE} 过界后必须落成 {@link TokenUsage#NONE}，
     * 而不是 NPE（图片已生成、已入库，却因为记账抛异常让整次工具调用失败）。</p>
     */
    @Test
    void billsAnEmptyUsageAsNoneRatherThanFailing() {
        BlogAgentImagePort imagePort = mock(BlogAgentImagePort.class);
        when(imagePort.isEnabled()).thenReturn(true);
        when(imagePort.generate(anyString())).thenReturn(
                image(new byte[]{1}, "image/png", "png", BlogAgentImageUsage.NONE, "m", "1536x1024"));
        MediaAssetPort mediaAssetPort = mock(MediaAssetPort.class);
        when(mediaAssetPort.recordGenerated(anyLong(), any(), anyString(), any(), anyString(), anyString()))
                .thenReturn(assetRef(9L, "https://cdn.example.test/image.png"));
        BillingPort billingPort = mock(BillingPort.class);
        ImageGenerationTool tool = newTool(imagePort, mediaAssetPort, billingPort);

        tool.execute(USER_ID, JsonNodeFactory.instance.objectNode().put("prompt", "x"), noOpProgress());

        verify(billingPort).chargeTokens(USER_ID, BillingBiz.BLOG_AGENT_ILLUSTRATION,
                "agent-image:9", "asset:9", TokenUsage.NONE, "m", "智能体生成图片");
    }

    /**
     * 风格前缀由<b>工具</b>拼，端口只收拼好的 prompt。
     *
     * <p>这一条同时钉住"风格没有被搬进契约"：端口收的就是这段前缀 + 用户描述。</p>
     */
    @Test
    void prefixesTheToolStyleOntoTheUserPromptBeforeCallingThePort() {
        BlogAgentImagePort imagePort = mock(BlogAgentImagePort.class);
        when(imagePort.isEnabled()).thenReturn(true);
        when(imagePort.generate(anyString())).thenReturn(
                image(new byte[]{1}, "image/png", "png", BlogAgentImageUsage.NONE, "m", "1536x1024"));
        MediaAssetPort mediaAssetPort = mock(MediaAssetPort.class);
        when(mediaAssetPort.recordGenerated(anyLong(), any(), anyString(), any(), anyString(), anyString()))
                .thenReturn(assetRef(9L, "https://cdn.example.test/image.png"));
        ImageGenerationTool tool = newTool(imagePort, mediaAssetPort, mock(BillingPort.class));

        tool.execute(USER_ID, JsonNodeFactory.instance.objectNode().put("prompt", "一只小猫"),
                noOpProgress());

        verify(imagePort).generate(ImageGenerationTool.TOOL_IMAGE_STYLE + "一只小猫");
    }

    private static ImageGenerationTool newTool(BlogAgentImagePort imagePort,
                                              MediaAssetPort mediaAssetPort,
                                              BillingPort billingPort) {
        return new ImageGenerationTool(imagePort, mediaAssetPort, billingPort, new ObjectMapper());
    }

    private static BlogAgentImage image(byte[] bytes, String contentType, String extension,
                                        BlogAgentImageUsage usage, String model, String size) {
        return new BlogAgentImage(bytes, contentType, extension, usage, model, size);
    }

    /**
     * 13 个字段按位构造太容易错位，测试里只关心 id 与 url，其余留空。
     * 字段顺序由 {@code MediaAssetRefTest} 单独钉住。
     */
    private static MediaAssetRef assetRef(Long id, String url) {
        return new MediaAssetRef(id, null, url, null, null, null, null, null, null, null, null, null, null);
    }

    private AgentTool.ProgressListener noOpProgress() {
        return new AgentTool.ProgressListener() {
            @Override
            public void onProgress(String message) { }

            @Override
            public void checkCancellation() { }
        };
    }
}
