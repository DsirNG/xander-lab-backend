package com.xander.lab.agent.infrastructure;

import com.xander.lab.integration.api.BlogSyncResult;
import com.xander.lab.integration.api.JuejinBlogSyncPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/** Juejin publication port implementation backed by platform-integration HTTP. */
@Component
@ConditionalOnProperty(prefix = "agent.platform-client", name = "enabled", havingValue = "true")
public class AgentJuejinBlogSyncHttpAdapter implements JuejinBlogSyncPort {

    private final AgentPlatformBlogSyncClient client;

    public AgentJuejinBlogSyncHttpAdapter(AgentPlatformBlogSyncClient client) {
        this.client = client;
    }

    @Override
    public BlogSyncResult sync(Long userId, Long postId,
                               String juejinCategoryId, List<String> juejinTagIds) {
        return client.syncJuejin(userId, postId, juejinCategoryId, juejinTagIds);
    }

    @Override
    public BlogSyncResult findSucceeded(Long userId, Long postId) {
        return client.findSucceededJuejin(userId, postId);
    }
}
