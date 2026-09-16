package com.xander.lab.publishing.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xander.lab.common.PageData;
import com.xander.lab.auth.core.UserContext;
import com.xander.lab.publishing.application.AgentImageVO;
import com.xander.lab.publishing.application.BlogMediaService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BlogMediaControllerTest {

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    void getAgentImagesPassesUserIdAndPaginationToService() {
        BlogMediaService service = mock(BlogMediaService.class);
        BlogMediaController controller = new BlogMediaController(service);
        UserContext.setUserId(42L);
        PageData<AgentImageVO> expected = PageData.of(new Page<>(2, 12));
        when(service.getAgentImages(42L, 2, 12)).thenReturn(expected);

        PageData<AgentImageVO> body = controller.getAgentImages(2, 12).getData();

        assertThat(body).isSameAs(expected);
        verify(service).getAgentImages(42L, 2, 12);
    }
}