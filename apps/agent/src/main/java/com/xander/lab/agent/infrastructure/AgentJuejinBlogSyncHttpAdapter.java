package com.xander.lab.agent.infrastructure;

import com.xander.lab.integration.api.BlogSyncResult;
import com.xander.lab.integration.api.JuejinBlogSyncPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** Juejin publication port implementation backed by platform-integration HTTP. */
@Component
@ConditionalOnProperty(prefix = "agent.platform-client", name = "enabled", havingValue = "true")
public class AgentJuejinBlogSyncHttpAdapter implements JuejinBlogSyncPort {

    private final AgentPlatformBlogSyncClient client;
    private final Optional<AgentContentPublicationClient> contentClient;

    public AgentJuejinBlogSyncHttpAdapter(AgentPlatformBlogSyncClient client) {
        this(client, Optional.empty());
    }

    @Autowired
    public AgentJuejinBlogSyncHttpAdapter(AgentPlatformBlogSyncClient client,
                                          Optional<AgentContentPublicationClient> contentClient) {
        this.client = client;
        this.contentClient = contentClient;
    }

    @Override
    public BlogSyncResult sync(Long userId, Long postId,
                               String juejinCategoryId, List<String> juejinTagIds) {
        return contentClient.map(value -> value.sync(userId, postId, "juejin"))
                .orElseGet(() -> client.syncJuejin(userId, postId, juejinCategoryId, juejinTagIds));
    }

    @Override
    public BlogSyncResult findSucceeded(Long userId, Long postId) {
        return contentClient.map(value -> value.findSucceeded(userId, postId, "juejin"))
                .orElseGet(() -> client.findSucceededJuejin(userId, postId));
    }
}
