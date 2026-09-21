package com.xander.lab.automation.host;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/** Optional MySQL scheduler lock for global content plan polling. */
@Configuration
@ConditionalOnProperty(prefix = "content-automation.shedlock", name = "enabled",
        havingValue = "true")
@EnableSchedulerLock(defaultLockAtMostFor = "PT5M")
public class ContentShedLockConfiguration {

    /** Uses database time so instances with clock skew cannot overlap a lock lease. */
    @Bean
    public LockProvider contentLockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .usingDbTime()
                        .build());
    }
}
