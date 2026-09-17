package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xander.lab.payment.api.BillingBiz;
import com.xander.lab.payment.api.BillingPort;
import com.xander.lab.payment.api.TokenUsage;
import com.xander.lab.common.ModelUsage;
import com.xander.lab.content.api.MediaAssetPort;
import com.xander.lab.content.api.MediaAssetRef;
import com.xander.lab.blogagent.infrastructure.BlogAgentImageClient;
import com.xander.lab.blogagent.infrastructure.BlogAgentProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class ImageGenerationToolTest {

    @Test
    void identifiesImageProviderFailuresWithoutExposingTheRawHttpError() {
        BlogAgentImageClient imageClient = mock(BlogAgentImageClient.class);
        when(imageClient.isEnabled()).thenReturn(true);
        when(imageClient.generate(anyString()))
                .thenThrow(new IllegalStateException("500 Internal Server Error: secret vendor body"));
        ImageGenerationTool tool = new ImageGenerationTool(
                imageClient, mock(MediaAssetPort.class), new BlogAgentProperties(),
                mock(BillingPort.class), new ObjectMapper());
        var args = JsonNodeFactory.instance.objectNode().put("prompt", "一只小猫");

        assertThatThrownBy(() -> tool.execute(1L, args, noOpProgress()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("图片生成模型调用失败，请检查图片模型接口配置或供应商状态")
                .hasMessageNotContaining("secret vendor body");
    }

    @Test
    void chargesImageGenerationByReportedTokenUsage() {
        BlogAgentImageClient imageClient = mock(BlogAgentImageClient.class);
        when(imageClient.isEnabled()).thenReturn(true);
        ModelUsage usage = new ModelUsage(120, 20, 80);
        when(imageClient.generate(anyString())).thenReturn(
                new BlogAgentImageClient.GeneratedImage(new byte[]{1}, "image/png", "png", usage, "gpt-image-2"));
        MediaAssetRef asset = assetRef(9L, "https://cdn.example.test/image.png");
        BillingPort billingPort = mock(BillingPort.class);
        MediaAssetPort mediaAssetPort = mock(MediaAssetPort.class);
        when(mediaAssetPort.recordGenerated(anyLong(), any(), anyString(), any(), anyString(), anyString()))
                .thenReturn(asset);
        ImageGenerationTool tool = new ImageGenerationTool(
                imageClient, mediaAssetPort, new BlogAgentProperties(), billingPort, new ObjectMapper());
        var args = JsonNodeFactory.instance.objectNode().put("prompt", "draw a robot");

        tool.execute(1L, args, noOpProgress());

        // 断言的是换算后的值而不是原对象：网关口径 ModelUsage(120,20,80) 必须按位置
        // 变成计费口径 TokenUsage(120,20,80)。实参顺序写反不会编译报错，只会静默错账，
        // 所以这里连位置一起钉住（见 TokenUsageTest）。
        verify(billingPort).chargeTokens(1L, BillingBiz.BLOG_AGENT_ILLUSTRATION,
                "agent-image:9", "asset:9", new TokenUsage(120, 20, 80), "gpt-image-2", "智能体生成图片");
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
