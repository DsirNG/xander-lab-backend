package com.xander.lab.automation.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.automation.host.ContentPlanExecutionProperties;
import com.xander.lab.automation.host.ContentPlanSchedulerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** Feature-gate tests for the content-owned scheduler boundary. */
class ContentBlogPlanRunExecutionServiceTest {

    @Test
    void schedulerDoesNotTouchPlanTablesBeforeBothGatesAreEnabled() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ContentPlanExecutionProperties executorProperties = new ContentPlanExecutionProperties();
        ContentPlanSchedulerProperties schedulerProperties = new ContentPlanSchedulerProperties();
        schedulerProperties.setEnabled(true);

        ContentBlogPlanRunExecutionService service = new ContentBlogPlanRunExecutionService(
                jdbcTemplate, null, new ObjectMapper(), null, null, null, null, null,
                null, null, null, null, null, executorProperties, schedulerProperties);

        service.dispatchDuePlans();

        verifyNoInteractions(jdbcTemplate);
    }
}
