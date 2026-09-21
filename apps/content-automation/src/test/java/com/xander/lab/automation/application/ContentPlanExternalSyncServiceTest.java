package com.xander.lab.automation.application;

import com.xander.lab.automation.infrastructure.ContentNotificationStreamService;
import com.xander.lab.automation.infrastructure.PlatformBlogSyncClient;
import com.xander.lab.integration.api.BlogSyncResult;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** Regression coverage for independent multi-platform publication outcomes. */
class ContentPlanExternalSyncServiceTest {

    @Test
    void oneVendorFailureDoesNotPreventTheOtherAttemptOrDurableFailureRecord() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PlatformBlogSyncClient client = mock(PlatformBlogSyncClient.class);
        ContentNotificationStreamService notificationStream = mock(ContentNotificationStreamService.class);
        ContentPlanExternalSyncService service = new ContentPlanExternalSyncService(
                jdbcTemplate, client, notificationStream);

        org.mockito.Mockito.when(client.syncCsdn(7L, 42L))
                .thenReturn(new BlogSyncResult("csdn", "csdn-42", "https://csdn.example/42"));
        doThrow(new IllegalStateException("juejin unavailable"))
                .when(client).syncJuejin(7L, 42L);

        service.sync(new ContentPlanExternalSyncService.ExternalSyncPlan(
                99L, 7L, 11L, "topic", 42L, true, true));

        verify(client).syncCsdn(7L, 42L);
        verify(client).syncJuejin(7L, 42L);
        verify(jdbcTemplate).update(any(String.class), eq("csdn-42"),
                eq("https://csdn.example/42"), eq(99L));
        verify(jdbcTemplate).update(any(String.class), eq("juejin unavailable"), eq(99L));
        verify(notificationStream).push(eq(7L), eq("JUEJIN_FAILED"), eq("掘金同步失败"),
                any(String.class), eq(11L), eq(99L));
    }
}
