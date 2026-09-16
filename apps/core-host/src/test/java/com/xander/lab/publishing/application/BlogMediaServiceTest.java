package com.xander.lab.publishing.application;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xander.lab.common.PageData;
import com.xander.lab.identity.security.UserContext;
import com.xander.lab.integration.oss.OssService;
import com.xander.lab.publishing.domain.BlogMediaAsset;
import com.xander.lab.publishing.infrastructure.BlogMediaAssetMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BlogMediaServiceTest {

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    private BlogMediaService serviceWith(BlogMediaAssetMapper mapper) {
        return new BlogMediaService(mapper, mock(OssService.class));
    }

    @Test
    void getAgentImagesFiltersUserAndAgentSourceAndParsesMeta() {
        BlogMediaAssetMapper mapper = mock(BlogMediaAssetMapper.class);
        BlogMediaService service = serviceWith(mapper);
        UserContext.setUserId(42L);

        BlogMediaAsset asset = new BlogMediaAsset();
        asset.setId(9L);
        asset.setUserId(42L);
        asset.setUrl("https://oss.example.com/photos/blog/agent/x.png");
        asset.setOriginalName("agent-image-1.png");
        asset.setSourceType("agent_generated");
        asset.setGenerationMeta("{\"title\":\"女孩漫画\",\"prompt\":\"创建女孩漫画\","
                + "\"style\":\"tool\",\"model\":\"gpt-image-1\",\"size\":\"1536x1024\"}");
        asset.setWidth(1536);
        asset.setHeight(1024);
        asset.setCreatedAt(LocalDateTime.of(2026, 8, 16, 10, 0));
        Page<BlogMediaAsset> page = new Page<>(1, 24, 1);
        page.setRecords(List.of(asset));
        when(mapper.selectPage(any(), any())).thenReturn(page);

        PageData<AgentImageVO> result = service.getAgentImages(42L, 1, 24);

        assertThat(result.getRecords()).hasSize(1);
        AgentImageVO vo = result.getRecords().get(0);
        assertThat(vo.id()).isEqualTo(9L);
        assertThat(vo.title()).isEqualTo("女孩漫画");
        assertThat(vo.prompt()).isEqualTo("创建女孩漫画");
        assertThat(vo.model()).isEqualTo("gpt-image-1");
        assertThat(vo.size()).isEqualTo("1536x1024");
        assertThat(vo.width()).isEqualTo(1536);
        assertThat(vo.height()).isEqualTo(1024);
        assertThat(vo.createdAt()).isEqualTo(LocalDateTime.of(2026, 8, 16, 10, 0));
    }

    @Test
    void getAgentImagesFallsBackToOriginalNameWhenMetaIsCorrupt() {
        BlogMediaAssetMapper mapper = mock(BlogMediaAssetMapper.class);
        BlogMediaService service = serviceWith(mapper);
        UserContext.setUserId(42L);

        BlogMediaAsset asset = new BlogMediaAsset();
        asset.setId(10L);
        asset.setUserId(42L);
        asset.setUrl("https://oss.example.com/photos/blog/agent/y.png");
        asset.setOriginalName("agent-image-2.png");
        asset.setSourceType("agent_generated");
        asset.setGenerationMeta("not-json");
        Page<BlogMediaAsset> page = new Page<>(1, 24, 1);
        page.setRecords(List.of(asset));
        when(mapper.selectPage(any(), any())).thenReturn(page);

        PageData<AgentImageVO> result = service.getAgentImages(42L, 1, 24);

        assertThat(result.getRecords()).hasSize(1);
        assertThat(result.getRecords().get(0).title()).isEqualTo("agent-image-2.png");
        assertThat(result.getRecords().get(0).prompt()).isNull();
    }
}