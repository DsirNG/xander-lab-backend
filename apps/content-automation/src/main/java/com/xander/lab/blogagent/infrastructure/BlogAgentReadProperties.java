package com.xander.lab.blogagent.infrastructure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Read-side BlogAgent settings owned by content-automation during Wave 2. */
@Data
@Component
@ConfigurationProperties(prefix = "blog.agent")
public class BlogAgentReadProperties {
    /** Running tasks older than this many minutes are recovered on detail read. */
    private int staleTaskMinutes = 30;
}
