package com.xander.lab.agent.infrastructure;

import com.xander.lab.integration.api.BlogSyncResult;
import com.xander.lab.integration.api.CsdnBlogSyncPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** CSDN publication port implementation backed by platform-integration HTTP. */
@Component
@ConditionalOnProperty(prefix = "agent.platform-client", name = "enabled", havingValue = "true")
public class AgentCsdnBlogSyncHttpAdapter implements CsdnBlogSyncPort {

    private final AgentPlatformBlogSyncClient client;

    public AgentCsdnBlogSyncHttpAdapter(AgentPlatformBlogSyncClient client) {
        this.client = client;
    }

    @Override
    public BlogSyncResult sync(Long userId, Long postId) {
        return client.syncCsdn(userId, postId);
    }

    @Override
    public BlogSyncResult findSucceeded(Long userId, Long postId) {
        return client.findSucceededCsdn(userId, postId);
    }
}
