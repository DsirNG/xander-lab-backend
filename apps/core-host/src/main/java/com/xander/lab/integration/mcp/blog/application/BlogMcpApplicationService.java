package com.xander.lab.integration.mcp.blog.application;

import com.xander.lab.auth.core.UserContext;
import com.xander.lab.content.api.ContentPost;
import com.xander.lab.content.api.ContentPostInput;
import com.xander.lab.content.api.ContentPostPage;
import com.xander.lab.content.api.ContentPostPort;
import com.xander.lab.content.api.ContentPostQuery;
import com.xander.lab.integration.mcp.security.McpOAuthRequestContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Applies MCP blog commands as the user bound to the calling MCP client.
 *
 * <p>依赖内容域的 {@link ContentPostPort} 而不是它的 {@code BlogService}：integration 在
 * {@code dinqor-platform-integration}、内容域在 {@code dinqor-content-automation}，
 * 拆完是两个进程，直连具体 {@code @Service} 就是“一个服务调另一个服务的内部类”。</p>
 *
 * <p>端口显式收 {@code userId}，所以 {@link #asUser} 里那句 {@code UserContext.setUserId}
 * 现在<b>已经是冗余的</b>（内容域的实现全部走参数，不再读 ThreadLocal）。保留它是因为
 * 这条路径一直依赖它、而它本身无害；要删就是一次独立的清理，不该夹在契约抽取里 ——
 * 删错了会让 {@code UserContext} 泄漏到下一条复用该线程的请求上。</p>
 */
@Service
@RequiredArgsConstructor
public class BlogMcpApplicationService {
    private final ContentPostPort contentPostPort;

    public boolean isAuthorized() { return currentUserId() != null; }

    public ContentPostPage list(Integer page, Integer size, String search) {
        return asUser(userId -> contentPostPort.listMyPosts(userId,
                new ContentPostQuery(null, search, null, null),
                page == null ? 1 : page, size == null ? 10 : size));
    }

    public ContentPost get(Long id) {
        return asUser(userId -> contentPostPort.findOwned(userId, id));
    }

    public ContentPost create(ContentPostInput post, boolean publish) {
        return asUser(userId -> contentPostPort.create(userId, post, publish));
    }

    public ContentPost update(Long id, String title, String summary, String content, String categoryId, List<String> tags) {
        return asUser(userId -> contentPostPort.update(userId, id,
                new ContentPostInput(title, summary, content, categoryId, null, tags)));
    }

    public void delete(Long id) {
        asUser(userId -> { contentPostPort.moveToTrash(userId, id); return null; });
    }

    /** Returns the DinQorAI user represented by the client bound to this request. */
    public Long currentUserId() {
        return McpOAuthRequestContext.userId();
    }

    private <T> T asUser(java.util.function.Function<Long, T> command) {
        Long userId = currentUserId();
        if (userId == null) throw new McpAuthorizationRequiredException();
        try {
            UserContext.setUserId(userId);
            return command.apply(userId);
        } finally {
            UserContext.clear();
        }
    }

    /** Raised when an MCP client has not completed browser authorization. */
    public static class McpAuthorizationRequiredException extends RuntimeException { }
}
