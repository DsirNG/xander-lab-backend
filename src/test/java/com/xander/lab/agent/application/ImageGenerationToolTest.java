package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xander.lab.billing.application.BillingService;
import com.xander.lab.blogagent.infrastructure.BlogAgentImageClient;
import com.xander.lab.blogagent.infrastructure.BlogAgentProperties;
import com.xander.lab.publishing.application.BlogMediaService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ImageGenerationToolTest {

    @Test
    void identifiesImageProviderFailuresWithoutExposingTheRawHttpError() {
        BlogAgentImageClient imageClient = mock(BlogAgentImageClient.class);
        when(imageClient.isEnabled()).thenReturn(true);
        when(imageClient.generate(anyString()))
                .thenThrow(new IllegalStateException("500 Internal Server Error: secret vendor body"));
        ImageGenerationTool tool = new ImageGenerationTool(
                imageClient, mock(BlogMediaService.class), new BlogAgentProperties(),
                mock(BillingService.class), new ObjectMapper());
        var args = JsonNodeFactory.instance.objectNode().put("prompt", "一只小猫");

        assertThatThrownBy(() -> tool.execute(1L, args, noOpProgress()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("图片生成模型调用失败，请检查图片模型接口配置或供应商状态")
                .hasMessageNotContaining("secret vendor body");
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
