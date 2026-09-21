package com.xander.lab.agent.infrastructure;

import com.xander.lab.content.api.ContentPost;
import com.xander.lab.content.api.ContentPostInput;
import com.xander.lab.content.api.ContentPostPage;
import com.xander.lab.content.api.ContentPostPort;
import com.xander.lab.content.api.ContentPostQuery;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

/** 通过 content-automation 内网契约 API 调用内容文章服务。 */
@Component
@ConditionalOnProperty(prefix = "agent.content-client", name = "enabled", havingValue = "true")
public class AgentContentPostHttpAdapter implements ContentPostPort {
    private static final String INTERNAL_TOKEN_HEADER = "X-DinQor-Internal-Token";

    private final RestClient restClient;
    private final String internalToken;

    @Autowired
    public AgentContentPostHttpAdapter(RestClient.Builder builder,
                                       AgentContentClientProperties properties) {
        if (properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()) {
            throw new IllegalStateException("agent.content-client.base-url 在启用 content-client 时不能为空");
        }
        if (properties.getInternalToken() == null || properties.getInternalToken().isBlank()) {
            throw new IllegalStateException(
                    "agent.content-client.internal-token 在启用 content-client 时不能为空");
        }
        String baseUrl = properties.getBaseUrl().endsWith("/")
                ? properties.getBaseUrl().substring(0, properties.getBaseUrl().length() - 1)
                : properties.getBaseUrl();
        this.restClient = builder.baseUrl(baseUrl).build();
        this.internalToken = properties.getInternalToken();
    }

    @Override
    public ContentPostPage listMyPosts(Long userId, ContentPostQuery query, int page, int size) {
        return post("/internal/agent/content/list-my-posts",
                new ListMyPostsRequest(userId, query == null ? ContentPostQuery.NONE : query, page, size),
                ContentPostPage.class);
    }

    @Override
    public List<ContentPost> listAllMyPosts(Long userId, ContentPostQuery query, Integer limit) {
        List<ContentPost> response = post("/internal/agent/content/list-all-my-posts",
                new ListAllMyPostsRequest(userId, query == null ? ContentPostQuery.NONE : query, limit),
                ContentPostList.class).posts();
        return response == null ? List.of() : response;
    }

    @Override
    public ContentPost findOwned(Long userId, Long postId) {
        return post("/internal/agent/content/find-owned", new FindOwnedRequest(userId, postId),
                ContentPost.class);
    }

    @Override
    public long countManaged(Long userId) {
        CountResponse response = post("/internal/agent/content/count-managed", new UserRequest(userId),
                CountResponse.class);
        return response == null ? 0L : response.count();
    }

    @Override
    public ContentPost create(Long userId, ContentPostInput input, boolean publish) {
        return post("/internal/agent/content/create", new CreateRequest(userId, input, publish),
                ContentPost.class);
    }

    @Override
    public ContentPost update(Long userId, Long postId, ContentPostInput input) {
        return post("/internal/agent/content/update", new UpdateRequest(userId, postId, input),
                ContentPost.class);
    }

    @Override
    public void moveToTrash(Long userId, Long postId) {
        post("/internal/agent/content/move-to-trash", new FindOwnedRequest(userId, postId), Void.class);
    }

    private <T> T post(String path, Object body, Class<T> responseType) {
        try {
            return restClient.post()
                    .uri(path)
                    .header(INTERNAL_TOKEN_HEADER, internalToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(responseType);
        } catch (RestClientException exception) {
            throw new IllegalStateException("content-automation content boundary unavailable", exception);
        }
    }

    private record ListMyPostsRequest(Long userId, ContentPostQuery query, int page, int size) {
    }

    private record ListAllMyPostsRequest(Long userId, ContentPostQuery query, Integer limit) {
    }

    private record FindOwnedRequest(Long userId, Long postId) {
    }

    private record UserRequest(Long userId) {
    }

    private record CreateRequest(Long userId, ContentPostInput input, boolean publish) {
    }

    private record UpdateRequest(Long userId, Long postId, ContentPostInput input) {
    }

    private record CountResponse(long count) {
    }

    private record ContentPostList(List<ContentPost> posts) {
    }
}
