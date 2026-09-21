package com.xander.lab.agent.infrastructure;

import com.xander.lab.integration.api.BlogSyncResult;
import com.xander.lab.integration.api.CsdnBlogSyncPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** CSDN publication port implementation backed by platform-integration HTTP. */
@Component
@ConditionalOnProperty(prefix = "agent.platform-client", name = "enabled", havingValue = "true")
public class AgentCsdnBlogSyncHttpAdapter implements CsdnBlogSyncPort {

    private final AgentPlatformBlogSyncClient client;
    private final Optional<AgentContentPublicationClient> contentClient;

    public AgentCsdnBlogSyncHttpAdapter(AgentPlatformBlogSyncClient client) {
        this(client, Optional.empty());
    }

    @Autowired
    public AgentCsdnBlogSyncHttpAdapter(AgentPlatformBlogSyncClient client,
                                        Optional<AgentContentPublicationClient> contentClient) {
        this.client = client;
        this.contentClient = contentClient;
    }

    @Override
    public BlogSyncResult sync(Long userId, Long postId) {
        return contentClient.map(value -> value.sync(userId, postId, "csdn"))
                .orElseGet(() -> client.syncCsdn(userId, postId));
    }

    @Override
    public BlogSyncResult findSucceeded(Long userId, Long postId) {
        return contentClient.map(value -> value.findSucceeded(userId, postId, "csdn"))
                .orElseGet(() -> client.findSucceededCsdn(userId, postId));
    }
}
