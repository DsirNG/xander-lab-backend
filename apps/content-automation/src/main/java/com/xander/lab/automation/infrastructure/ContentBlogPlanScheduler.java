package com.xander.lab.automation.infrastructure;

import com.xander.lab.automation.application.ContentBlogPlanRunExecutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls and recovers content-owned durable plan runs when the feature gate is enabled. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "content-automation.plan-scheduler", name = "enabled",
        havingValue = "true")
public class ContentBlogPlanScheduler {

    private final ContentBlogPlanRunExecutionService executionService;

    /** The lock narrows polling to one instance; the database claim remains authoritative. */
    @Scheduled(fixedDelayString = "${content-automation.plan-scheduler.poll-interval-ms:15000}",
            initialDelayString = "${content-automation.plan-scheduler.initial-delay-ms:15000}")
    @SchedulerLock(name = "content-blog-plan-scheduler", lockAtMostFor = "PT2M")
    public void dispatchDuePlans() {
        try {
            executionService.dispatchDuePlans();
        } catch (RuntimeException exception) {
            log.error("[BlogPlan] content poller failed", exception);
        }
    }
}
