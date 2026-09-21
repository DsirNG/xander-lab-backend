package com.xander.lab.automation.api;

import com.xander.lab.automation.application.ContentBlogPlanRunExecutionService;
import com.xander.lab.automation.infrastructure.BlogPlanTriggerClient;
import com.xander.lab.common.Result;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BlogPlanTriggerControllerTest {

    @Test
    void enabledContentExecutorOwnsManualTriggerInsteadOfCoreBridge() {
        BlogPlanTriggerClient legacyClient = mock(BlogPlanTriggerClient.class);
        ContentBlogPlanRunExecutionService contentExecutor =
                mock(ContentBlogPlanRunExecutionService.class);
        when(contentExecutor.enabled()).thenReturn(true);
        BlogPlanRunVO expected = BlogPlanRunVO.builder().id(99L).planId(7L).build();
        when(contentExecutor.triggerManual(42L, 7L)).thenReturn(expected);

        BlogPlanTriggerController controller =
                new BlogPlanTriggerController(legacyClient, contentExecutor);

        Result<BlogPlanRunVO> result = controller.trigger("42", 7L);

        assertThat(result.getData()).isEqualTo(expected);
        verify(contentExecutor).triggerManual(42L, 7L);
        verify(legacyClient, never()).trigger(42L, 7L);
    }
}
